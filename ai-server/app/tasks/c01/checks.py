"""T1 C-01 staff pre-check node registered in T2's shared executor."""
from __future__ import annotations

from dataclasses import replace

from app.core.staff_engine.models import CheckRequest, CheckResponse, ReviewItem
from app.core.staff_engine.registry import CheckRegistry


def c01_staff_precheck(request: CheckRequest) -> list[ReviewItem]:
    snapshot = request.case_snapshot
    items: list[ReviewItem] = []
    if not snapshot.get('reason') or not snapshot.get('currentSituation'):
        items.append(ReviewItem(source='PRE_SUBMISSION', category='CUSTOMER_FACTS', result='NEEDS_CUSTOMER_CONFIRMATION', message='신청 사유와 현재 상황을 고객에게 추가 확인해야 합니다.', basis_doc_id='C01-01'))
    for rule in snapshot.get('ruleResults', []):
        code = rule.get('ruleCode') if isinstance(rule, dict) else getattr(rule, 'ruleCode', None)
        disposition = rule.get('disposition') if isinstance(rule, dict) else getattr(rule, 'disposition', None)
        if disposition == 'NEEDS_STAFF_REVIEW' and code in {'C1-R5', 'C1-W1', 'C1-W2', 'C1-W3'}:
            items.append(ReviewItem(source='SPRING_RULE', category=code, result='NEEDS_STAFF_REVIEW', message=(rule.get('message') if isinstance(rule, dict) else rule.message), basis_doc_id='C01-01', evidence={'ruleCode': code}))
    if not items:
        items.append(ReviewItem(source='PRE_SUBMISSION', category='C01_INTAKE', result='NEEDS_STAFF_REVIEW', message='시연 접수 항목을 직원이 확인해야 합니다.', basis_doc_id='C01-01'))
    return items


def adapt_c01_response(response: CheckResponse) -> CheckResponse:
    """T1 adapter for the additional-customer-confirmation branch without altering the T2 engine."""
    if response.status == 'SUCCEEDED' and any(item.result == 'NEEDS_CUSTOMER_CONFIRMATION' for item in response.review_items):
        return replace(response, review_status='WAITING_CUSTOMER')
    return response


def register_c01_check(registry: CheckRegistry) -> None:
    registry.register('DEBT_ADJUSTMENT', 'STAFF_PRECHECK', c01_staff_precheck)
