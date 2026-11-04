from __future__ import annotations

import hashlib
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[3]
AI_SERVER = ROOT / "ai-server"
sys.path.insert(0, str(AI_SERVER))

from app.document_analysis.router import create_router  # noqa: E402

TOKEN = "document-analysis-test-token"
TEMPLATE = AI_SERVER / "app/document_analysis/templates/b01_201903.pdf"
BACKEND_TEMPLATE = ROOT / "backend/src/main/resources/b01/mock-ocr/b01_blank_201903.pdf"
FIXTURES = AI_SERVER / "tests/fixtures/b01_opencv"
FILLED_PDF = ROOT / "backend/src/main/resources/b01/mock-ocr/b01-t01-application.pdf"


@pytest.fixture(scope="module")
def client():
    app = FastAPI()
    app.include_router(create_router(internal_token=TOKEN), prefix="/api/v1")
    return TestClient(app)


def post(client: TestClient, path: Path, content_type: str, request_id: str = "image-analysis-1"):
    return client.post(
        "/api/v1/internal/document-analysis/b01",
        headers={"X-Customer-Agent-Token": TOKEN},
        data={"requestId": request_id},
        files={"file": (path.name, path.read_bytes(), content_type)},
    )


def test_template_is_packaged_with_identical_reviewed_bytes():
    assert hashlib.sha256(TEMPLATE.read_bytes()).digest() == hashlib.sha256(BACKEND_TEMPLATE.read_bytes()).digest()


def test_requires_existing_internal_service_token(client):
    payload = {"requestId": "auth"}
    files = {"file": ("document.png", (FIXTURES / "unchecked.png").read_bytes(), "image/png")}
    assert client.post("/api/v1/internal/document-analysis/b01", data=payload, files=files).status_code == 401
    assert client.post(
        "/api/v1/internal/document-analysis/b01",
        headers={"X-Customer-Agent-Token": "wrong"}, data=payload, files=files,
    ).status_code == 401


def test_reports_unconfigured_auth_without_accepting_requests():
    app = FastAPI()
    app.include_router(create_router(internal_token=""), prefix="/api/v1")
    response = TestClient(app).post(
        "/api/v1/internal/document-analysis/b01",
        data={"requestId": "unconfigured"},
        files={"file": ("document.png", (FIXTURES / "unchecked.png").read_bytes(), "image/png")},
    )
    assert response.status_code == 503
    assert response.json()["detail"] == "DOCUMENT_ANALYSIS_AUTH_NOT_CONFIGURED"


@pytest.mark.parametrize(
    ("name", "selected", "first", "second", "signature", "uncertain"),
    [
        ("first_checked", "PRINCIPAL_FIRST", "CHECKED", "UNCHECKED", "TRACE_ABSENT", True),
        ("second_checked", "INTEREST_FIRST", "UNCHECKED", "CHECKED", "TRACE_ABSENT", True),
        ("unchecked", "NONE", "UNCHECKED", "UNCHECKED", "TRACE_ABSENT", True),
        ("both_checked", "MULTIPLE", "CHECKED", "CHECKED", "TRACE_ABSENT", True),
        ("faint_checked", "UNCERTAIN", "UNCERTAIN", "UNCHECKED", "TRACE_ABSENT", True),
        ("signature_present", "NONE", "UNCHECKED", "UNCHECKED", "TRACE_PRESENT", True),
        ("signature_absent", "NONE", "UNCHECKED", "UNCHECKED", "TRACE_ABSENT", True),
    ],
)
def test_png_fixture_contract(client, name, selected, first, second, signature, uncertain):
    response = post(client, FIXTURES / f"{name}.png", "image/png", f"fixture-{name}")
    assert response.status_code == 200
    assert response.json() == {
        "requestId": f"fixture-{name}",
        "formCode": "5-202-0557",
        "alignmentStatus": "ALIGNED",
        "firstBox": first,
        "secondBox": second,
        "selectedOrder": selected,
        "debtorSignatureTrace": signature,
        "uncertain": uncertain,
        "manualVerificationRequired": True,
    }


def test_pdf_with_one_check_and_signature_is_not_marked_uncertain(client):
    response = post(client, FILLED_PDF, "application/pdf", "filled-pdf")
    assert response.status_code == 200
    assert response.json()["selectedOrder"] == "PRINCIPAL_FIRST"
    assert response.json()["debtorSignatureTrace"] == "TRACE_PRESENT"
    assert response.json()["uncertain"] is False
    assert response.json()["manualVerificationRequired"] is True


def test_jpeg_is_decoded_by_content_and_analyzed(client, tmp_path):
    source = cv2.imread(str(FIXTURES / "second_checked.png"), cv2.IMREAD_GRAYSCALE)
    encoded = cv2.imencode(".jpg", source, [cv2.IMWRITE_JPEG_QUALITY, 100])[1].tobytes()
    path = tmp_path / "second.jpg"
    path.write_bytes(encoded)
    response = post(client, path, "image/jpeg", "jpeg")
    assert response.status_code == 200
    assert response.json()["selectedOrder"] == "INTEREST_FIRST"


def test_alignment_failure_is_a_fail_closed_analysis_result(client, tmp_path):
    encoded = cv2.imencode(".png", np.full((900, 650), 255, dtype=np.uint8))[1].tobytes()
    path = tmp_path / "blank-image.png"
    path.write_bytes(encoded)
    response = post(client, path, "image/png", "alignment-failed")
    assert response.status_code == 200
    assert response.json()["alignmentStatus"] == "ALIGNMENT_FAILED"
    assert response.json()["selectedOrder"] == "UNCERTAIN"
    assert response.json()["debtorSignatureTrace"] == "UNCERTAIN"
    assert response.json()["uncertain"] is True


def test_rejects_declared_type_or_magic_mismatch(client, tmp_path):
    path = tmp_path / "fake.png"
    path.write_bytes(b"not-an-image")
    response = post(client, path, "image/png", "bad-format")
    assert response.status_code == 415
    assert response.json()["detail"] == "UNSUPPORTED_DOCUMENT_TYPE"


def test_rejects_oversized_file_before_analysis():
    app = FastAPI()
    app.include_router(create_router(internal_token=TOKEN, max_file_bytes=16), prefix="/api/v1")
    response = TestClient(app).post(
        "/api/v1/internal/document-analysis/b01",
        headers={"X-Customer-Agent-Token": TOKEN}, data={"requestId": "large"},
        files={"file": ("large.png", b"\x89PNG\r\n\x1a\n" + b"x" * 32, "image/png")},
    )
    assert response.status_code == 413


def test_internal_analysis_exception_returns_safe_error():
    def broken_analyzer(_reference, _candidate):
        raise RuntimeError("sensitive internal failure")

    app = FastAPI()
    app.include_router(create_router(internal_token=TOKEN, analyzer=broken_analyzer), prefix="/api/v1")
    response = post(TestClient(app), FIXTURES / "unchecked.png", "image/png", "failure")
    assert response.status_code == 500
    assert response.json() == {"detail": "DOCUMENT_ANALYSIS_FAILED"}
    assert "sensitive" not in response.text
