from __future__ import annotations

import os
from fastapi import APIRouter, Header, HTTPException
from app.core.staff_engine import CheckRegistry, CheckRequest, FakeCasePort, StaffCheckExecutor
from app.core.staff_engine.fakes import FakeCase
from app.core.staff_engine.rag_adapter import RagEvidenceAdapter
from app.tasks.c01 import adapt_c01_response, register_c01_check
from app.tasks.c02 import register_c02_check


def build_executor() -> StaffCheckExecutor:
    registry = CheckRegistry()
    register_c01_check(registry)
    register_c02_check(registry)
    # Spring verifies ownership/current revision before calling this internal API.
    # This in-process port only gates the graph against the supplied revision.
    case_port = FakeCasePort([])
    executor = StaffCheckExecutor(case_port, registry, RagEvidenceAdapter(lambda *_: []))
    executor._http_case_port = case_port  # composition-only fixture; Spring remains source of truth.
    return executor


def create_router(executor: StaffCheckExecutor | None = None) -> APIRouter:
    router = APIRouter(tags=['staff-agent'])
    selected = executor or build_executor()

    @router.post('/staff-agent/check')
    def check(payload: dict, x_staff_agent_token: str | None = Header(default=None)) -> dict:
        expected = os.getenv('STAFF_AGENT_GATEWAY_TOKEN')
        if not expected or x_staff_agent_token != expected:
            raise HTTPException(403, 'STAFF_AGENT_GATEWAY_FORBIDDEN')
        try:
            request = CheckRequest(
                request_id=payload['requestId'], case_id=payload['caseId'], revision=payload['revision'],
                task_code=payload['taskCode'], check_type=payload.get('checkType', 'STAFF_PRECHECK'),
                case_snapshot=payload.get('caseSnapshot', {}), rag_query=payload.get('ragQuery'), audience='staff',
            )
        except (KeyError, ValueError) as exc:
            raise HTTPException(422, str(exc)) from exc
        # Spring must send this only after its CasePort revision/ownership check.  The
        # local fixture preserves that first known revision so a later stale call is
        # stopped before the registered handler/RAG nodes run.
        case_port = getattr(selected, '_http_case_port', None)
        if case_port is not None and request.case_id not in case_port._cases:
            case_port._cases[request.case_id] = FakeCase(request.case_id, request.revision, request.task_code)
        response = selected.run(request)
        if request.task_code == 'DEBT_ADJUSTMENT':
            response = adapt_c01_response(response)
        return response.as_dict()

    return router
