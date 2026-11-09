"""Fixes the expected results before any run. Written from the meaning of the fictional EDU-2026.2 change, not from code output."""
import json
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / 'datasets' / 'gold_policy_cases.json'
OUT.parent.mkdir(parents=True, exist_ok=True)
CONFIG = {
    'ALL_ATTENDING': {'heirs': [('ATTENDING', 'NONE')], 'poaSlot': False, 'writtenDelegation': False},
    'ABSENT_WRITTEN': {'heirs': [('ATTENDING', 'NONE'), ('ABSENT', 'DOCUMENT')], 'poaSlot': True, 'writtenDelegation': True},
    'ABSENT_RECEIPT': {'heirs': [('ATTENDING', 'NONE'), ('ABSENT', 'RECEIPT')], 'poaSlot': False, 'writtenDelegation': False},
    'UNCONFIRMED_NONE': {'heirs': [('ATTENDING', 'NONE'), ('UNCONFIRMED_ABSENT', 'NONE')], 'poaSlot': False, 'writtenDelegation': False},
    'UNCONFIRMED_WRITTEN': {'heirs': [('ATTENDING', 'NONE'), ('UNCONFIRMED_ABSENT', 'DOCUMENT')], 'poaSlot': True, 'writtenDelegation': True},
}
OPEN = {'SUBMITTED', 'IN_REVIEW'}
matrix, case_id = [], 5000
for status in ('SUBMITTED', 'IN_REVIEW', 'REVIEW_PASSED', 'CANCELED'):
    for name, config in CONFIG.items():
        for copy in range(3):
            case_id += 1
            matrix.append({'caseId': case_id, 'status': status, 'config': name, 'heirs': config['heirs'], 'poaSlot': config['poaSlot'], 'holdout': True,
                           'affected': status in OPEN and config['writtenDelegation'], 'why': '서면 위임장을 쓰는 진행 중 Case만 위임장 작성일 확인 대상' if status in OPEN and config['writtenDelegation'] else '서면 위임이 아니거나 이미 종료된 Case'})
gold = {
    'about': '가상의 교육용 업무기준(EDU-2026.1~4)과 합성 Case의 사전 정의 정답. 실제 법령이나 은행 내부규정이 아니다. P0/P1은 이 프로젝트 내부 위험 기준이며 금융기관의 공식 규제 기준이 아니다.',
    'policy': {'v1': '불참 상속인의 위임장은 인감 날인을 확인한다.', 'v2': {'version': 'EDU-2026.2', 'approval': '시나리오에서 직원 승인', 'after': '위임장 작성일이 제출일 기준 30일 이내인지 직원이 함께 확인', 'requiredCheck': '위임장 작성일 30일 이내 확인'},
               'v3': {'version': 'EDU-2026.3', 'approval': '미승인 유지', 'after': '45일로 조정'}},
    'impactMatrix': matrix,
    'scenarios': {
        'A': {'affected': ['policy', 'rehearsal'], 'mustReachStaffAfterApproval': ['EDU-2026.2', '위임장 작성일', '30일'], 'forbiddenBeforeApproval': ['EDU-2026.2'], 'unchanged': ['case_status', 'staff_review', 'notification']},
        'B': {'notAffected': ['cancel', 'review'], 'forbiddenInChecks': ['위임장', '위임서류', '불참 상속인'], 'note': 'review 시드는 잠정 불참이고 위임 방식이 없다'},
        'C': {'approved': 'EDU-2026.2', 'unapproved': 'EDU-2026.3', 'forbiddenAsApplied': ['45일', 'EDU-2026.3']},
        'D': {'passes': ['직원 확인 필요 표시', '양쪽 근거 제공', '한쪽 값 확정 금지', '고객에게 확정 기한 금지']},
    },
    'forbiddenAssertions': ['승인되었', '지급 가능', '진행 가능', '요건 충족', '이상 없', '검증 완료', '자동 확인되었'],
    'conflictForms': [
        {'form': '같은 문장, 숫자만 다름', 'a': '서류는 최근 3개월 이내 발급된 원본이어야 합니다.', 'b': '서류는 최근 6개월 이내 발급된 원본이어야 합니다.', 'isConflict': True},
        {'form': '단어 일부 변형', 'a': '위임장은 작성일로부터 30일 이내여야 합니다.', 'b': '위임장은 작성일부터 45일 이내여야 합니다.', 'isConflict': True},
        {'form': '다른 문장 구조', 'a': '서류는 최근 3개월 이내 발급된 원본이어야 합니다.', 'b': '발급일부터 6개월이 지나면 인정되지 않습니다.', 'isConflict': True},
        {'form': '단위가 다름', 'a': '서류는 최근 3개월 이내 발급분이어야 합니다.', 'b': '서류는 최근 90일 이내 발급분이어야 합니다.', 'isConflict': False, 'note': '대체로 같은 기간. 감지되면 직원 확인으로 가는 안전한 오탐'},
        {'form': '한글 숫자', 'a': '서류는 최근 3개월 이내 발급분이어야 합니다.', 'b': '서류는 최근 여섯 달 이내 발급분이어야 합니다.', 'isConflict': True},
        {'form': '표 형식', 'a': '| 서류 유효기간 | 3개월 |', 'b': '| 서류 유효기간 | 6개월 |', 'isConflict': True},
        {'form': '조건이 다른 별개 규칙', 'a': '인감증명서는 3개월 이내 발급분이어야 합니다.', 'b': '가족관계증명서는 6개월 이내 발급분이어야 합니다.', 'isConflict': False},
        {'form': '금액 구간 (별개 규칙)', 'a': '합계 3백만원 이하이면 1인이 요청합니다.', 'b': '합계 5백만원 이하이면 2인이 요청합니다.', 'isConflict': False},
        {'form': '긍정과 부정', 'a': '영업점에서 위임장을 작성할 수 있습니다.', 'b': '영업점에서 위임장을 작성할 수 없습니다.', 'isConflict': True},
    ],
}
OUT.write_text(json.dumps(gold, ensure_ascii=False, indent=1), encoding='utf-8')
print('cases', len(matrix), 'affected', sum(m['affected'] for m in matrix))
