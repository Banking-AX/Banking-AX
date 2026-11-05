from __future__ import annotations

import hmac
import os
from pathlib import Path
from typing import Callable, Literal

from fastapi import APIRouter, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field

from .b01_opencv import B01ImageAnalysis, analyze, render_document, render_document_bytes


FORM_CODE = "5-202-0557"
DEFAULT_TEMPLATE = Path(__file__).resolve().parent / "templates" / "b01_201903.pdf"
SUPPORTED_TYPES = {
    "application/pdf": b"%PDF-",
    "image/png": b"\x89PNG\r\n\x1a\n",
    "image/jpeg": b"\xff\xd8\xff",
}


class B01ImageAnalysisResponse(BaseModel):
    requestId: str
    formCode: Literal["5-202-0557"] = FORM_CODE
    alignmentStatus: Literal["ALIGNED", "ALIGNMENT_FAILED", "TEMPLATE_MISMATCH"]
    firstBox: Literal["CHECKED", "UNCHECKED", "UNCERTAIN"]
    secondBox: Literal["CHECKED", "UNCHECKED", "UNCERTAIN"]
    selectedOrder: Literal["PRINCIPAL_FIRST", "INTEREST_FIRST", "NONE", "MULTIPLE", "UNCERTAIN"]
    debtorSignatureTrace: Literal["TRACE_PRESENT", "TRACE_ABSENT", "UNCERTAIN"]
    uncertain: bool
    manualVerificationRequired: bool = True


def _max_bytes(value: int | None) -> int:
    if value is not None:
        return value
    try:
        parsed = int(os.getenv("B01_IMAGE_ANALYSIS_MAX_BYTES", "10485760"))
    except ValueError as exc:
        raise RuntimeError("B01 image analysis maximum file size is invalid.") from exc
    if parsed <= 0:
        raise RuntimeError("B01 image analysis maximum file size must be positive.")
    return parsed


def _content_type(upload: UploadFile, content: bytes) -> str:
    declared = (upload.content_type or "").lower().strip()
    signature = SUPPORTED_TYPES.get(declared)
    if signature is None or not content.startswith(signature):
        raise HTTPException(415, "UNSUPPORTED_DOCUMENT_TYPE")
    return declared


def _response(request_id: str, result: B01ImageAnalysis) -> B01ImageAnalysisResponse:
    uncertain = (
        result.alignment_status != "ALIGNED"
        or result.first_checkbox == "UNCERTAIN"
        or result.second_checkbox == "UNCERTAIN"
        or result.selected_order in ("NONE", "MULTIPLE", "UNCERTAIN")
        or result.debtor_signature != "TRACE_PRESENT"
    )
    return B01ImageAnalysisResponse(
        requestId=request_id,
        alignmentStatus=result.alignment_status,
        firstBox=result.first_checkbox,
        secondBox=result.second_checkbox,
        selectedOrder=result.selected_order,
        debtorSignatureTrace=result.debtor_signature,
        uncertain=uncertain,
        manualVerificationRequired=True,
    )


def create_router(
    internal_token: str | None = None,
    template_path: str | Path | None = None,
    max_file_bytes: int | None = None,
    analyzer: Callable = analyze,
) -> APIRouter:
    router = APIRouter(tags=["internal-document-analysis"])
    expected_token = (internal_token if internal_token is not None else
                      os.getenv("CUSTOMER_AGENT_INTERNAL_TOKEN", "")).strip()
    limit = _max_bytes(max_file_bytes)
    reference_path = Path(template_path) if template_path is not None else DEFAULT_TEMPLATE
    try:
        reference = render_document(reference_path)
    except Exception as exc:
        raise RuntimeError("B-01 reference template could not be loaded.") from exc

    def require_internal_token(token: str | None) -> None:
        if not expected_token:
            raise HTTPException(503, "DOCUMENT_ANALYSIS_AUTH_NOT_CONFIGURED")
        if token is None or not hmac.compare_digest(token, expected_token):
            raise HTTPException(401, "DOCUMENT_ANALYSIS_AUTH_REQUIRED")

    @router.post("/internal/document-analysis/b01", response_model=B01ImageAnalysisResponse)
    async def analyze_b01_document(
        request_id: str = Form(alias="requestId", min_length=1, max_length=64),
        file: UploadFile = File(),
        token: str | None = Header(default=None, alias="X-Customer-Agent-Token"),
    ) -> B01ImageAnalysisResponse:
        require_internal_token(token)
        content = await file.read(limit + 1)
        if not content:
            raise HTTPException(400, "EMPTY_DOCUMENT")
        if len(content) > limit:
            raise HTTPException(413, "DOCUMENT_TOO_LARGE")
        content_type = _content_type(file, content)
        try:
            candidate = render_document_bytes(content, content_type)
            return _response(request_id, analyzer(reference, candidate))
        except HTTPException:
            raise
        except ValueError as exc:
            raise HTTPException(422, "DOCUMENT_ANALYSIS_INPUT_INVALID") from exc
        except Exception as exc:
            raise HTTPException(500, "DOCUMENT_ANALYSIS_FAILED") from exc

    return router
