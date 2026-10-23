"""C-02 Phase 0 check: identifies candidates, never decides inheritance eligibility."""
from __future__ import annotations

from app.core.staff_engine.models import CheckRequest, ReviewItem
from app.core.staff_engine.registry import CheckRegistry


def c02_staff_precheck(request: CheckRequest) -> list[ReviewItem]:
    heirs = request.case_snapshot.get('heirs', [])
    unconfirmed = any(heir.get('visitStatus') == 'UNCONFIRMED_ABSENT' for heir in heirs)
    if unconfirmed:
        return [ReviewItem(
            source='AGENT_SEMANTIC', category='VISIT_ATTENDANCE', result='NEEDS_STAFF_REVIEW',
            message='방문 여부 미응답 상속인은 잠정 불참일 뿐이며 직원 확인이 필요합니다.',
            evidence={'caseStatement': 'UNCONFIRMED_ABSENT is not a legal determination'},
        )]
    return [ReviewItem(
        source='PRE_SUBMISSION', category='DOCUMENT_POLICY', result='NEEDS_STAFF_REVIEW',
        message='일반 증명서 원문은 OCR 미적용이며 직원 원문 확인이 필요합니다.',
        evidence={'documentExtraction': {'method': 'NONE', 'status': 'NOT_APPLICABLE'}},
    )]


def register_c02_check(registry: CheckRegistry) -> None:
    registry.register('INHERITANCE_DEPOSIT', 'STAFF_PRECHECK', c02_staff_precheck)
