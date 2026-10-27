"""C-02 Phase 0 check: identifies candidates, never decides inheritance eligibility."""
from __future__ import annotations

from app.core.staff_engine.models import CheckRequest, ReviewItem
from app.core.staff_engine.registry import CheckRegistry


def _document_manifest_items(manifest: list[dict]) -> list[ReviewItem]:
    """Translate Spring's document metadata into review items without reading file bodies."""
    items: list[ReviewItem] = []
    for document in manifest:
        if document.get('requirement') != 'REQUIRED':
            continue
        status = document.get('status', 'NOT_SUBMITTED')
        evidence = {'requiredDocument': document.get('slot'), 'scope': document.get('scope'), 'status': status}
        if document.get('personRef'):
            evidence['personRef'] = document['personRef']
        if document.get('documentVersion') is not None:
            evidence['documentVersion'] = document['documentVersion']
        if status == 'NOT_SUBMITTED':
            items.append(ReviewItem(source='SPRING_RULE', category='DOCUMENT_MISSING', result='NEEDS_STAFF_REVIEW', message='Required document has not been submitted.', document_file_id=document.get('documentFileId'), evidence=evidence))
        elif status in ('VALIDATION_FAILED', 'NEEDS_SUPPLEMENT'):
            items.append(ReviewItem(source='SPRING_RULE', category='DOCUMENT_NEEDS_SUPPLEMENT', result='NEEDS_STAFF_REVIEW', message='Submitted document requires correction or staff confirmation.', document_file_id=document.get('documentFileId'), evidence=evidence))
        elif document.get('documentFileId') is None:
            items.append(ReviewItem(source='SPRING_RULE', category='DOCUMENT_METADATA_MISSING', result='NEEDS_STAFF_REVIEW', message='Document status has no persisted file metadata; staff confirmation is required.', evidence=evidence))
    return items


def c02_staff_precheck(request: CheckRequest) -> list[ReviewItem]:
    snapshot = request.case_snapshot
    heirs = snapshot.get('heirs', [])
    items: list[ReviewItem] = []
    if not snapshot.get('decedent', {}).get('deathDate') or not heirs:
        items.append(ReviewItem(source='SPRING_RULE', category='REQUIRED_INPUT', result='NEEDS_STAFF_REVIEW', message='피상속인 사망일 또는 상속인 정보가 누락되어 직원 확인이 필요합니다.'))
    for code in snapshot.get('outOfScope', []):
        items.append(ReviewItem(source='SPRING_RULE', category='SCOPE', result='NEEDS_STAFF_REVIEW', message=f'C-02 사전접수 범위 밖 조건: {code}', evidence={'ruleCode': code}))
    manifest = snapshot.get('requiredDocuments')
    if isinstance(manifest, list) and manifest:
        items.extend(_document_manifest_items(manifest))
        snapshot = {**snapshot, 'missingDocuments': []}
    for code in snapshot.get('missingDocuments', []):
        items.append(ReviewItem(source='SPRING_RULE', category='DOCUMENT_SLOT', result='NEEDS_STAFF_REVIEW', message='필수 서류 슬롯 미충족', evidence={'requiredDocument': code}))
    for code in snapshot.get('ruleResults', []):
        items.append(ReviewItem(source='SPRING_RULE', category='INTAKE_RULE', result='NEEDS_STAFF_REVIEW', message='Spring 규칙 결과를 직원이 확인해야 합니다.', evidence={'ruleCode': code}))
    unconfirmed = any(heir.get('visitStatus') == 'UNCONFIRMED_ABSENT' for heir in heirs)
    if unconfirmed:
        items.append(ReviewItem(
            source='AGENT_SEMANTIC', category='VISIT_ATTENDANCE', result='NEEDS_STAFF_REVIEW',
            message='방문 여부 미응답 상속인은 잠정 불참일 뿐이며 직원 확인이 필요합니다.',
            evidence={'caseStatement': 'UNCONFIRMED_ABSENT is not a legal determination'},
        ))
    items.append(ReviewItem(
        source='PRE_SUBMISSION', category='DOCUMENT_POLICY', result='NEEDS_STAFF_REVIEW',
        message='일반 증명서 원문은 OCR 미적용이며 직원 원문 확인이 필요합니다.',
        evidence={'documentExtraction': {'method': 'NONE', 'status': 'NOT_APPLICABLE'}},
    ))
    return items


def register_c02_check(registry: CheckRegistry) -> None:
    registry.register('INHERITANCE_DEPOSIT', 'STAFF_PRECHECK', c02_staff_precheck)
