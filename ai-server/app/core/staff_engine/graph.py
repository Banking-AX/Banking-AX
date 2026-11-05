"""LangGraph employee pre-check skeleton with a testable no-dependency fallback.

The real graph is built when ``langgraph`` is installed (declared in
requirements.txt).  The fallback has the same ``invoke`` boundary solely so
Phase 0 contract tests can run before dependencies are installed; it is not a
replacement for LangGraph in a deployed service.
"""
from __future__ import annotations

from collections.abc import Callable
from typing import Any, TypedDict

from .fakes import FakeCasePort, PortError
from .models import CheckRequest, CheckResponse, PolicyEvidence, ReviewItem
from .rag_adapter import RagEvidenceAdapter
from .registry import CheckRegistry


class StaffCheckState(TypedDict, total=False):
    request: CheckRequest
    review_items: list[ReviewItem]
    policy_evidence: list[PolicyEvidence]
    error: dict[str, str] | None


def _manual_item(code: str, message: str) -> ReviewItem:
    return ReviewItem(
        source='AGENT_SEMANTIC', category='SYSTEM_HANDOFF', result='NEEDS_STAFF_REVIEW',
        message=message, evidence={'errorCode': code},
    )


class _FallbackGraph:
    """Minimal sequential runner used only when langgraph is unavailable locally."""
    def __init__(self, nodes: list[Callable[[StaffCheckState], dict[str, Any]]]) -> None:
        self._nodes = nodes

    def invoke(self, state: StaffCheckState) -> StaffCheckState:
        output = dict(state)
        for node in self._nodes:
            output.update(node(output))
            if output.get('error'):
                break
        return output


def build_staff_check_graph(case_port: FakeCasePort, registry: CheckRegistry, rag: RagEvidenceAdapter):
    def load_case(state: StaffCheckState) -> dict[str, Any]:
        request = state['request']
        try:
            case_port.get_case(request.case_id, request.revision, request.task_code)
        except PortError as exc:
            return {'error': {'code': exc.code, 'message': 'Spring CasePort rejected the pre-check request.'}}
        return {}

    def execute_check(state: StaffCheckState) -> dict[str, Any]:
        try:
            return {'review_items': registry.execute(state['request'])}
        except LookupError as exc:
            return {'error': {'code': str(exc), 'message': 'No declared task check is registered.'}}
        except TimeoutError:
            return {'error': {'code': 'TIMEOUT', 'message': 'Employee pre-check timed out.'}}
        except Exception:
            return {'error': {'code': 'CHECK_FAILED', 'message': 'Employee pre-check failed; staff review is required.'}}

    def attach_rag(state: StaffCheckState) -> dict[str, Any]:
        request = state['request']
        if not request.rag_query:
            return {'policy_evidence': []}
        try:
            evidence = rag.search(request.rag_query, request.task_code, request.audience)
        except Exception:
            return {'error': {'code': 'RAG_UNAVAILABLE', 'message': 'Policy evidence is unavailable; staff review is required.'}}
        items = list(state.get('review_items', []))
        if not evidence:
            items.append(_manual_item('RAG_NO_EVIDENCE', '업무 규정 근거를 찾지 못해 직원 확인이 필요합니다.'))
        elif any(not row.verified for row in evidence):
            items.append(_manual_item('RAG_UNVERIFIED', '검수되지 않은 근거가 반환되어 직원 확인이 필요합니다.'))
        # Spring persists review items only, so verified policy evidence is carried as reference items
        # (basis_doc_id + section). They inform the staff member and never pass a Case.
        for row in evidence:
            if row.verified:
                items.append(ReviewItem(
                    source='AGENT_SEMANTIC', category='POLICY_EVIDENCE', result='REFERENCE',
                    message=f'업무 근거: {row.title} – {row.section}', basis_doc_id=row.doc_id,
                    evidence={'policySection': row.section, 'sourcePages': row.source_pages},
                ))
        return {'review_items': items, 'policy_evidence': evidence}

    # Keep actual LangGraph wiring explicit.  The graph has no node that writes
    # a Case, reservation, notification, or staff final result.
    try:
        from langgraph.graph import END, START, StateGraph
    except ImportError:
        return _FallbackGraph([load_case, execute_check, attach_rag])

    graph = StateGraph(StaffCheckState)
    graph.add_node('load_case', load_case)
    graph.add_node('execute_check', execute_check)
    graph.add_node('attach_rag', attach_rag)
    graph.add_edge(START, 'load_case')

    def continue_if_no_error(state: StaffCheckState) -> str:
        return END if state.get('error') else 'continue'

    graph.add_conditional_edges('load_case', continue_if_no_error, {'continue': 'execute_check', END: END})
    graph.add_conditional_edges('execute_check', continue_if_no_error, {'continue': 'attach_rag', END: END})
    graph.add_edge('attach_rag', END)
    return graph.compile()


class StaffCheckExecutor:
    """Request-id idempotency wrapper around the staff pre-check graph."""
    def __init__(self, case_port: FakeCasePort, registry: CheckRegistry, rag: RagEvidenceAdapter) -> None:
        self._graph = build_staff_check_graph(case_port, registry, rag)
        self._responses: dict[str, tuple[tuple[object, ...], CheckResponse]] = {}

    def run(self, request: CheckRequest) -> CheckResponse:
        existing = self._responses.get(request.request_id)
        if existing:
            if existing[0] != request.fingerprint():
                return self._failure(request, 'DUPLICATE_REQUEST_CONFLICT', 'requestId was reused for another request.')
            return existing[1]

        state = self._graph.invoke({'request': request})
        error = state.get('error')
        if error:
            response = self._failure(request, error['code'], error['message'])
        else:
            response = CheckResponse(
                request_id=request.request_id, case_id=request.case_id, revision=request.revision,
                task_code=request.task_code, check_type=request.check_type, status='SUCCEEDED',
                review_status='READY_FOR_STAFF', review_items=state.get('review_items', []),
                policy_evidence=state.get('policy_evidence', []),
            )
        self._responses[request.request_id] = (request.fingerprint(), response)
        return response

    @staticmethod
    def _failure(request: CheckRequest, code: str, message: str) -> CheckResponse:
        return CheckResponse(
            request_id=request.request_id, case_id=request.case_id, revision=request.revision,
            task_code=request.task_code, check_type=request.check_type, status='FAILED',
            review_status='FAILED_NEEDS_STAFF', review_items=[_manual_item(code, message)],
            error={'code': code, 'message': message},
        )
