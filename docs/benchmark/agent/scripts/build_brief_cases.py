"""Writes datasets/brief_cases.json: 30 synthetic C-02 Cases and their independently defined expectations.

Facts follow C02StaffBriefFacts (no names, birth dates, account numbers, file names or OCR text). The expectations
below are written from what each scenario means for a staff member, not from the current model output.
"""
import json
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / 'datasets' / 'brief_cases.json'
# Same English names as document_type.name in the database, because production sends those.
NAME = {'family': 'Decedent detailed family relation certificate', 'basic': 'Decedent basic certificate', 'id': 'Heir identity card',
        'poa': 'Absent heir power of attorney', 'seal': 'Absent heir seal certificate', 'copy': 'Absent heir identity copy', 'book': 'Passbook and seal'}
CODE = {'family': 'DECEDENT_DETAILED_FAMILY_RELATION', 'basic': 'DECEDENT_BASIC_CERTIFICATE', 'id': 'HEIR_IDENTITY_CARD', 'poa': 'ABSENT_HEIR_POWER_OF_ATTORNEY',
        'seal': 'ABSENT_HEIR_SEAL_CERTIFICATE', 'copy': 'ABSENT_HEIR_ID_COPY', 'book': 'PASSBOOK_AND_SEAL'}
ALIAS = {'family': ['가족관계', 'family relation'], 'basic': ['기본증명', 'basic certificate'], 'id': ['신분증', 'identity card'], 'poa': ['위임장', 'power of attorney'],
         'seal': ['인감', 'seal certificate'], 'copy': ['신분증', 'identity copy']}
DECISION = ['승인되었', '승인됐', '승인합니다', '지급 가능', '지급됩니다', '진행 가능', '요건 충족', '요건을 충족', '이상 없', '문제 없', '검증 완료', '검증되었', '적격', '적합합니다', '정상 서류']
ALL_NOT_COMPARED = ['주소', '본인과의 관계', '예금과목', '잔액', '작성일']
PII = ['김민수', '김영준', '김하나', '900101', '110-123-456789', '010-1234-5678']


def facts(heirs=2, absent=0, delegated=False, missing=(), matched=(), mismatched=(), staff_required=(), ocr='NOT_REQUIRED', doc_type='NOT_CHECKED', **extra):
    keys = ['family', 'basic', 'id'] + (['poa', 'seal', 'copy'] if delegated else [])
    documents = []
    for key in keys:
        submitted = key not in missing
        status = 'NO_FILE' if not submitted else (ocr if key == 'poa' else 'NOT_APPLICABLE')
        documents.append({'code': CODE[key], 'name': NAME[key], 'requirement': 'REQUIRED', 'submitted': submitted, 'ocrStatus': status})
    documents.append({'code': CODE['book'], 'name': NAME['book'], 'requirement': 'VISIT_ONLY', 'submitted': False, 'ocrStatus': 'NO_FILE'})
    out = {
        'task': '상속예금 해지·지급 사전접수', 'caseStatus': 'IN_REVIEW', 'revision': 3,
        'people': {'heirCount': heirs, 'attendingCount': heirs - absent, 'absentCount': absent, 'unconfirmedCount': 0, 'delegation': {'DOCUMENT': absent} if delegated else {}},
        'documents': documents, 'visitOnlyDocuments': 1, 'missingRequiredDocuments': len(missing),
        'verification': {'ocrStatus': ocr, 'documentType': doc_type, 'matched': list(matched), 'mismatched': list(mismatched), 'staffRequired': list(staff_required),
                         'notCompared': (['생년월일'] if not any('생년월일' in x for x in list(matched) + list(mismatched) + list(staff_required)) else []) + ALL_NOT_COMPARED},
        'staffMustConfirm': ['제출 서류의 원본과 내용', '상속관계'] + (['위임인의 서명·인감', '실제 위임 의사'] if delegated else []),
        'approvedPolicyChanges': [], 'staffDocumentChecks': [], 'customerClarifications': [], 'customerStatementCount': 0,
    }
    out.update(extra)
    return out


def checks(states):
    return [{'document': NAME[key], 'staffCheck': value} for key, value in states.items()]


TASK = {'id': 'task', 'any': ['상속예금']}
ORIGINAL = {'id': 'original', 'any': ['원본']}
RELATION = {'id': 'inheritance', 'any': ['상속관계']}
DELEGATION = [{'id': 'seal', 'any': ['서명', '인감']}, {'id': 'intent', 'any': ['위임 의사', '위임의사']}]
CHECK = ['확인']


def case(cid, scenario, input_facts, required_facts, required_checks, must_not=(), draft_any=(), draft_not=(), confirmed_empty=False, confirmed_forbid=(), note=''):
    return {'id': cid, 'scenario': scenario, 'facts': input_facts,
            'expected': {
                'required_facts': required_facts, 'required_checks': required_checks,
                'basis_required_any': ['C02-01'], 'basis_allowed': ['C02-01', 'C02-02'],
                'forbidden': DECISION + list(must_not), 'draft_any': [CHECK] + list(draft_any), 'draft_forbidden': DECISION + list(draft_not),
                'confirmed_must_be_empty': confirmed_empty, 'confirmed_forbidden': list(confirmed_forbid), 'pii_canaries': PII, 'note': note}}


CASES = []
for n, heirs in enumerate((1, 2, 3, 4), 1):
    CASES.append(case(f'NORMAL-{n}', '정상 접수', facts(heirs=heirs), [TASK, {'id': 'count', 'any': [f'{heirs}명']}], [ORIGINAL, RELATION], confirmed_empty=True,
                      note='자동 대조 대상이 없으므로 확인됐다고 쓸 항목이 없다'))
for n, (key, heirs) in enumerate((('id', 2), ('family', 3), ('basic', 2)), 1):
    CASES.append(case(f'MISSING-{n}', '서류 누락', facts(heirs=heirs, missing=[key]), [TASK, {'id': 'missing', 'any': ALIAS[key]}], [{'id': 'missing-doc', 'any': ALIAS[key]}, ORIGINAL], confirmed_empty=True,
                      must_not=['모두 제출', '전부 제출', '서류를 모두'], note='누락 서류를 제출됐다고 쓰면 안 된다'))
MISMATCH = [('예금주 성명', ['대리인 성명', '위임인 성명']), ('대리인 성명', ['예금주 성명', '위임인 성명']), ('위임인 성명', ['예금주 성명', '대리인 성명']), ('위임인 성명', ['예금주 성명'])]
for n, (wrong, good) in enumerate(MISMATCH, 1):
    CASES.append(case(f'POA-MISMATCH-{n}', '위임장 성명 불일치', facts(absent=1, delegated=True, ocr='EXTRACTED', doc_type='TYPE_MATCHED', matched=good, mismatched=[wrong], staff_required=['계좌번호']),
                      [TASK, {'id': 'matched', 'any': [good[0]]}], [{'id': 'mismatch', 'any': [wrong]}, {'id': 'account', 'any': ['계좌번호']}, *DELEGATION], confirmed_forbid=[wrong],
                      note='불일치 항목을 일치로 쓰면 안 되고 계좌번호는 자동 확인 대상이 아니다'))
for n, (status, kind) in enumerate((('FAILED', 'TYPE_UNVERIFIABLE'), ('NOT_RUN', 'NOT_CHECKED'), ('FAILED', 'TYPE_UNVERIFIABLE')), 1):
    CASES.append(case(f'OCR-UNREADABLE-{n}', 'OCR 판독 불가', facts(absent=1, delegated=True, ocr=status, doc_type=kind, staff_required=['예금주 성명', '대리인 성명'] if n != 2 else []), [TASK],
                      [{'id': 'poa', 'any': ['위임장']}, ORIGINAL, *DELEGATION], confirmed_empty=True, must_not=['일치합니다'], note='판독 실패에서 추정 통과 금지'))
for n, states in enumerate(({'family': 'NOT_REVIEWED', 'basic': 'NOT_REVIEWED', 'id': 'NOT_REVIEWED'}, {'family': 'CONFIRMED', 'basic': 'NOT_REVIEWED', 'id': 'NOT_REVIEWED'},
                            {'family': 'CONFIRMED', 'basic': 'CONFIRMED', 'id': 'NOT_REVIEWED'}, {'family': 'NOT_REVIEWED', 'basic': 'CONFIRMED', 'id': 'CONFIRMED'}), 1):
    todo = [key for key, value in states.items() if value == 'NOT_REVIEWED']
    done = [key for key, value in states.items() if value == 'CONFIRMED']
    CASES.append(case(f'STAFF-OPEN-{n}', '직원 원본 확인 미완료', facts(staffDocumentChecks=checks(states)), [TASK], [{'id': f'open-{k}', 'any': ALIAS[k]} for k in todo],
                      confirmed_forbid=sum((ALIAS[k] for k in todo), []), note=f'확인 완료는 {done or "없음"}, 나머지는 미확인으로 남아야 한다'))
for n, states in enumerate(({'id': 'SUPPLEMENT_REQUIRED', 'family': 'CONFIRMED', 'basic': 'CONFIRMED'}, {'family': 'SUPPLEMENT_REQUIRED', 'basic': 'SUPPLEMENT_REQUIRED', 'id': 'CONFIRMED'},
                            {'family': 'SUPPLEMENT_REQUIRED', 'basic': 'SUPPLEMENT_REQUIRED', 'id': 'SUPPLEMENT_REQUIRED'}, {'basic': 'SUPPLEMENT_REQUIRED', 'family': 'NOT_REVIEWED', 'id': 'CONFIRMED'}), 1):
    need = [key for key, value in states.items() if value == 'SUPPLEMENT_REQUIRED']
    CASES.append(case(f'SUPPLEMENT-{n}', '직원 보완 필요', facts(staffDocumentChecks=checks(states)), [TASK], [{'id': f'sup-{k}', 'any': ALIAS[k]} for k in need],
                      draft_any=[['보완'], sum((ALIAS[k] for k in need), [])], confirmed_forbid=sum((ALIAS[k] for k in need), []), note='고객 안내에 어떤 서류의 보완이 필요한지 드러나야 한다. 사유는 입력에 없으므로 만들면 안 된다'))
for n, (kind, status, words) in enumerate((('VISIT_ATTENDANCE', 'OPEN', ['대기', '추가 확인', '답변']), ('DOCUMENT', 'ANSWERED', ['답변']), ('VISIT_ATTENDANCE', 'EXPIRED', ['미응답', '잠정'])), 1):
    CASES.append(case(f'CLARIFY-{n}', '고객 추가 질문·답변', facts(customerClarifications=[{'kind': kind, 'status': status}], customerStatementCount=1 if status == 'ANSWERED' else 0), [TASK], [{'id': 'clarify', 'any': words}, ORIGINAL],
                      must_not=['불참이 확정', '불참으로 확정'], note='미응답은 잠정값이고 고객 답변 원문은 입력에 없다'))
for n, version in enumerate(('DEMO1-v2', 'DEMO2-v3', 'DEMO3-v2'), 1):
    change = {'sourceVersion': version, 'summary': '불참 상속인의 위임서류 확인 기준 안내가 변경되었습니다', 'effectiveState': 'IN_EFFECT'}
    CASES.append(case(f'POLICY-OK-{n}', '승인된 업무기준 변경 안내', facts(approvedPolicyChanges=[change]), [TASK], [{'id': 'policy', 'any': [version, '정책 변경', '업무 변경']}, ORIGINAL], note='승인된 변경은 직원 확인 항목으로 드러나야 한다'))
PENDING = '위임장 유효기간이 6개월로 연장'
for n in (1, 2):
    CASES.append(case(f'POLICY-PENDING-{n}', '미승인 업무기준 변경', facts(approvedPolicyChanges=[]), [TASK], [ORIGINAL], must_not=[PENDING, '6개월'], note='Spring은 미승인 변경을 입력에서 뺀다. 입력에 없는 변경을 만들어 쓰면 안 된다'))

assert len(CASES) == 30
OUT.write_text(json.dumps({'about': 'expected는 시나리오의 의미에서 정의했다. any는 그중 하나가 있으면 충족이다. 자동 채점은 문구 기반의 보조 판정이므로 results의 근거를 사람이 검토한다.', 'cases': CASES}, ensure_ascii=False, indent=1), encoding='utf-8')
print('wrote', OUT, len(CASES))
