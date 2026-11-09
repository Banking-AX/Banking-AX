from __future__ import annotations

import os
from fastapi import APIRouter, Body, Header, HTTPException
from app.core.staff_engine import CheckRegistry, CheckRequest, FakeCasePort, StaffCheckExecutor
from app.core.staff_engine.fakes import FakeCase
from app.core.staff_engine.rag_adapter import RagEvidenceAdapter, keyword_rag_search
from app.staff_agent.case_brief import default_brief_llm, write_brief
from app.staff_agent.policy_impact import analyze, default_policy_llm
from app.staff_agent.policy_question import answer_question, default_question_llm
from app.tasks.c01 import adapt_c01_response, register_c01_check
from app.tasks.c02 import register_c02_check


# Spring sends no ragQuery yet; C-02 gets a fixed policy query so the result sheet cites the rules it used.
DEFAULT_RAG_QUERY = {'INHERITANCE_DEPOSIT': '상속예금 지급 필요서류 상속인 방문 위임장 인감증명서'}


def build_executor() -> StaffCheckExecutor:
    registry = CheckRegistry()
    register_c01_check(registry)
    register_c02_check(registry)
    # Spring verifies ownership/current revision before calling this internal API.
    # This in-process port only gates the graph against the supplied revision.
    case_port = FakeCasePort([])
    # Policy evidence comes from the existing curated index (keyword strategy, no model load).
    # STAFF_AGENT_RAG_ENABLED=false keeps the previous evidence-free behaviour.
    rag_enabled = os.getenv('STAFF_AGENT_RAG_ENABLED', 'true').strip().lower() != 'false'
    executor = StaffCheckExecutor(case_port, registry, RagEvidenceAdapter(keyword_rag_search if rag_enabled else (lambda *_: [])))
    executor._http_case_port = case_port  # composition-only fixture; Spring remains source of truth.
    return executor


def create_router(executor: StaffCheckExecutor | None = None) -> APIRouter:
    router = APIRouter(tags=['staff-agent'])
    selected = executor or build_executor()

    @router.post('/staff-agent/check')
    # Explicit Body avoids framework-version-dependent inference of a bare
    # ``dict`` annotation at this internal HTTP boundary.
    def check(payload: dict = Body(...), x_staff_agent_token: str | None = Header(default=None)) -> dict:
        expected = os.getenv('STAFF_AGENT_GATEWAY_TOKEN')
        if not expected or x_staff_agent_token != expected:
            raise HTTPException(403, 'STAFF_AGENT_GATEWAY_FORBIDDEN')
        try:
            request = CheckRequest(
                request_id=payload['requestId'], case_id=payload['caseId'], revision=payload['revision'],
                task_code=payload['taskCode'], check_type=payload.get('checkType', 'STAFF_PRECHECK'),
                case_snapshot=payload.get('caseSnapshot', {}),
                rag_query=payload.get('ragQuery') or DEFAULT_RAG_QUERY.get(payload['taskCode']), audience='staff',
            )
        except (KeyError, ValueError) as exc:
            raise HTTPException(422, str(exc)) from exc
        # Spring must send this only after its CasePort revision/ownership check.  The
        # local fixture preserves that first known revision so a later stale call is
        # stopped before the registered handler/RAG nodes run.
        # A newer revision (customer answer or file supplement) advances the fixture; an older one is still stopped.
        case_port = getattr(selected, '_http_case_port', None)
        if case_port is not None:
            known = case_port._cases.get(request.case_id)
            if known is None or request.revision > known.revision:
                case_port._cases[request.case_id] = FakeCase(request.case_id, request.revision, request.task_code)
        response = selected.run(request)
        if request.task_code == 'DEBT_ADJUSTMENT':
            response = adapt_c01_response(response)
        return response.as_dict()

    @router.post('/staff-agent/policy-impact')
    def policy_impact(payload: dict = Body(...), x_staff_agent_token: str | None = Header(default=None)) -> dict:
        expected = os.getenv('STAFF_AGENT_GATEWAY_TOKEN')
        if not expected or x_staff_agent_token != expected:
            raise HTTPException(403, 'STAFF_AGENT_GATEWAY_FORBIDDEN')
        if not {'change', 'mappedTaskCodes', 'registeredTasks'} <= payload.keys():
            raise HTTPException(422, 'POLICY_IMPACT_REQUEST_INVALID')
        return analyze(payload, llm=default_policy_llm())

    @router.post('/staff-agent/case-brief')
    def case_brief(payload: dict = Body(...), x_staff_agent_token: str | None = Header(default=None)) -> dict:
        expected = os.getenv('STAFF_AGENT_GATEWAY_TOKEN')
        if not expected or x_staff_agent_token != expected:
            raise HTTPException(403, 'STAFF_AGENT_GATEWAY_FORBIDDEN')
        if not isinstance(payload.get('facts'), dict):
            raise HTTPException(422, 'CASE_BRIEF_REQUEST_INVALID')
        return write_brief(payload['facts'], llm=default_brief_llm(bool(os.getenv('OPENAI_API_KEY', '').strip())))

    @router.post('/staff-agent/policy-question')
    def policy_question(payload: dict = Body(...), x_staff_agent_token: str | None = Header(default=None)) -> dict:
        expected = os.getenv('STAFF_AGENT_GATEWAY_TOKEN')
        if not expected or x_staff_agent_token != expected:
            raise HTTPException(403, 'STAFF_AGENT_GATEWAY_FORBIDDEN')
        question, task_code = payload.get('question'), payload.get('taskCode')
        if not isinstance(question, str) or not 2 <= len(question.strip()) <= 300 or task_code not in DEFAULT_RAG_QUERY:
            raise HTTPException(422, 'POLICY_QUESTION_REQUEST_INVALID')
        return answer_question(question.strip(), task_code, llm=default_question_llm(bool(os.getenv('OPENAI_API_KEY', '').strip())))

    return router
