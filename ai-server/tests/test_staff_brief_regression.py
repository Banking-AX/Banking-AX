"""Failures seen in the 2026-11-09 real gpt-4.1-mini run (case-brief-5). The quoted model text is what it actually wrote."""
import json

import pytest

from app.staff_agent.case_brief import write_brief
from app.staff_agent.policy_question import answer_question


def documents(*rows):
    return [{'code': code, 'name': name, 'requirement': need, 'submitted': sent, 'ocrStatus': 'NOT_APPLICABLE'} for code, name, need, sent in rows]


COMMON = (('DECEDENT_DETAILED_FAMILY_RELATION', 'Decedent detailed family relation certificate', 'REQUIRED', True),
          ('DECEDENT_BASIC_CERTIFICATE', 'Decedent basic certificate', 'REQUIRED', True), ('HEIR_IDENTITY_CARD', 'Heir identity card', 'REQUIRED', True),
          ('PASSBOOK_AND_SEAL', 'Passbook and seal', 'VISIT_ONLY', False))


def facts(matched=(), mismatched=(), absent=0, rows=COMMON, **extra):
    return {'task': '상속예금 해지·지급 사전접수', 'people': {'heirCount': 3, 'absentCount': absent, 'unconfirmedCount': 0, 'delegation': {'DOCUMENT': absent} if absent else {}},
            'documents': documents(*rows), 'verification': {'ocrStatus': 'EXTRACTED' if matched or mismatched else 'NOT_REQUIRED', 'documentType': 'TYPE_MATCHED', 'matched': list(matched),
                                                             'mismatched': list(mismatched), 'staffRequired': [], 'notCompared': ['주소']},
            'staffMustConfirm': ['제출 서류의 원본과 내용', '상속관계'], **extra}


class Llm:
    def __init__(self, **output):
        self.output, self.sent = {'summary': '상속예금 사전접수를 신청했습니다.', 'confirmed': [], 'staffChecks': [], 'basis': [], 'customerDraft': '직원이 서류를 확인하고 있어요.', **output}, []

    def write(self, text):
        self.sent.append(text)
        return json.dumps(self.output, ensure_ascii=False)


def rag(*_):
    return [{'chunk_id': 'a', 'doc_id': 'C02-01', 'title': 'FAQ', 'section': '공통 유의사항', 'text': '서류는 원본으로 제출합니다.', 'verified': True, 'trust_level': 'OFFICIAL_SHINHAN'}]


def row(chunk, doc, text, **meta):
    return {'chunk_id': chunk, 'doc_id': doc, 'title': doc, 'section': '공통 유의사항', 'text': text, 'verified': True, 'trust_level': 'OFFICIAL_SHINHAN', **meta}


THREE, SIX = '서류는 최근 3개월 이내 발급된 원본이어야 합니다.', '서류는 최근 6개월 이내 발급된 원본이어야 합니다.'
conflicting = lambda *_: [row('a', 'C02-01', THREE), row('b', 'C02-02', SIX)]  # noqa: E731


@pytest.mark.parametrize('draft', [
    '상속예금 서류를 제출해 주셔서 감사합니다. 제출하신 서류는 자동 확인된 항목과 함께 직원이 현재 확인 중입니다.',  # NORMAL-1
    '제출하신 서류 중 자동 확인된 항목은 결과가 나오면 안내해 드릴게요.',  # NORMAL-2
    '위임장 관련 일치 항목은 자동 확인 결과이며, 확인 결과가 나오면 다시 안내할게요.',  # OCR-UNREADABLE-1
    '제출하신 서류 중 일부는 자동 확인되었고, 나머지는 직원이 현재 확인 중입니다.',  # STAFF-OPEN-3
])
def test_a_draft_cannot_claim_an_automatic_check_that_did_not_happen(draft):
    result = write_brief(facts(), search=rag, llm=Llm(customerDraft=draft))
    assert '자동' not in result['customerDraft'] and '확인' in result['customerDraft']


def test_saying_nothing_was_automatically_checked_is_true_and_stays():
    draft = '제출하신 서류 중 자동 확인된 항목은 없으며, 직원이 원본과 내용을 확인 중입니다.'
    assert write_brief(facts(), search=rag, llm=Llm(customerDraft=draft))['customerDraft'] == draft


def test_a_field_that_was_really_matched_may_be_named():
    draft = '위임장의 예금주 성명은 자동 확인 결과 일치해요. 직원이 나머지를 확인 중이에요.'
    assert write_brief(facts(matched=['예금주 성명'], absent=1), search=rag, llm=Llm(customerDraft=draft))['customerDraft'] == draft


@pytest.mark.parametrize('draft', ['위임장의 서명은 자동 확인되었어요.', '위임인 성명은 자동으로 확인되었어요.'])
def test_a_field_that_was_not_matched_cannot_be_named(draft):
    out = write_brief(facts(matched=['예금주 성명'], mismatched=['위임인 성명'], absent=1), search=rag, llm=Llm(customerDraft=draft))['customerDraft']
    assert draft not in out


def test_a_summary_cannot_claim_it_either():
    out = write_brief(facts(), search=rag, llm=Llm(summary='상속예금 접수이며 일부 서류는 자동 확인되었습니다.'))['summary']
    assert '자동 확인되었' not in out


@pytest.mark.parametrize('line', ['불참 상속인의 위임장 및 인감증명서 준비 여부 확인', '불참 상속인의 위임서류 준비 여부 확인', '상속인 출석 및 위임장 관련 서류 확인 필요'])
def test_no_absent_heir_means_no_delegation_check(line):
    assert line not in write_brief(facts(), search=rag, llm=Llm(staffChecks=[line]))['staffChecks']


def test_a_passbook_seal_check_is_not_a_delegation_check():
    line = '통장과 인감 방문 시 원본 확인 필요'
    assert line in write_brief(facts(), search=rag, llm=Llm(staffChecks=[line]))['staffChecks']


def test_an_absent_heir_keeps_the_delegation_check():
    line = '불참 상속인의 위임서류 준비 여부 확인'
    assert line in write_brief(facts(absent=1), search=rag, llm=Llm(staffChecks=[line]))['staffChecks']


def test_the_rule_draft_does_not_mention_delegation_without_an_absent_heir():
    assert '위임' not in write_brief(facts(), search=rag, llm=None)['customerDraft']


def test_conflicting_rules_get_no_answer_and_no_model_call():
    llm = Llm(sufficient=True, answer='서류는 최근 3개월 이내 발급된 원본이어야 합니다.', usedDocIds=['C02-01'])  # CON-1
    result = answer_question('서류는 몇 개월 이내 발급분이어야 하나요?', 'INHERITANCE_DEPOSIT', search=conflicting, llm=llm)
    assert result['status'] == 'INSUFFICIENT' and llm.sent == []
    assert {c['docId'] for c in result['citations']} == {'C02-01', 'C02-02'}
    assert '3개월' in result['answer'] and '6개월' in result['answer']


def test_conflicting_rules_become_a_staff_check_naming_both_sources():
    result = write_brief(facts(), search=conflicting, llm=Llm())  # CON-2
    line = next(text for text in result['staffChecks'] if '업무기준 충돌' in text)
    assert all(word in line for word in ('C02-01', 'C02-02', '3개월', '6개월'))
    assert result['basisConflicts'][0]['status'] == 'UNRESOLVED'


@pytest.mark.parametrize('field', ['summary', 'customerDraft'])
def test_text_cannot_pick_one_side_of_a_conflict(field):
    out = write_brief(facts(), search=conflicting, llm=Llm(**{field: '서류는 최근 3개월 이내 발급분이어야 해요.'}))[field]
    assert '3개월' not in out


def test_the_conflict_is_also_listed_without_the_model():
    assert any('업무기준 충돌' in line for line in write_brief(facts(), search=conflicting, llm=None)['staffChecks'])


def test_the_later_rule_in_effect_wins_only_when_both_are_approved_and_dated():
    from app.staff_agent.basis_conflict import find_conflicts, usable_evidence
    old = {'docId': 'A', 'section': 's', 'text': THREE, 'effectiveAt': '2025-01-01', 'approvedVersion': 'v1'}
    new = {'docId': 'B', 'section': 's', 'text': SIX, 'effectiveAt': '2026-01-01', 'approvedVersion': 'v2'}
    found = find_conflicts([old, new])
    assert found[0]['status'] == 'RESOLVED' and found[0]['applied'] == 'B'
    assert [row['docId'] for row in usable_evidence([old, new], found)] == ['B']


@pytest.mark.parametrize('other', [{'effectiveAt': '2099-01-01', 'approvedVersion': 'v2'}, {'effectiveAt': '2026-01-01'}, {'approvedVersion': 'v2'}, {}])
def test_a_future_or_unapproved_or_undated_rule_decides_nothing(other):
    from app.staff_agent.basis_conflict import find_conflicts
    old = {'docId': 'A', 'section': 's', 'text': THREE, 'effectiveAt': '2025-01-01', 'approvedVersion': 'v1'}
    assert find_conflicts([old, {'docId': 'B', 'section': 's', 'text': SIX, **other}])[0]['status'] == 'UNRESOLVED'


def test_unverified_material_cannot_create_or_settle_a_conflict():
    rows = lambda *_: [row('a', 'C02-01', THREE), {**row('b', 'X', SIX), 'verified': False}]  # noqa: E731
    llm = Llm()
    llm.output = {'sufficient': True, 'answer': '서류는 최근 3개월 이내 발급분이어야 해요.', 'usedDocIds': ['C02-01']}
    assert answer_question('기한은?', 'INHERITANCE_DEPOSIT', search=rows, llm=llm)['status'] == 'ANSWERED'


@pytest.mark.parametrize('a,b', [
    (THREE, THREE),  # the same rule repeated in two documents
    ('서류는 최근 3개월 이내 발급된 원본이어야 합니다.', '3개월 이상 변제계획을 이행하지 않으면 합의가 해지됩니다.'),  # different subjects
    ('합계 금액 3백만원 이하인 경우 상속인 중 1인에 의해 해지 요청이 가능합니다.', '상속인 모두 영업점 방문이 원칙입니다.'),
])
def test_different_or_identical_statements_are_not_a_conflict(a, b):
    from app.staff_agent.basis_conflict import find_conflicts
    assert find_conflicts([{'docId': 'A', 'section': 's', 'text': a}, {'docId': 'B', 'section': 's', 'text': b}]) == []


def test_two_tiers_in_one_document_are_not_a_conflict():
    from app.staff_agent.basis_conflict import find_conflicts
    assert find_conflicts([{'docId': 'A', 'section': 's', 'text': '합계 3백만원 이하이면 1인이 요청합니다.'}, {'docId': 'A', 'section': 't', 'text': '합계 5백만원 이하이면 1인이 요청합니다.'}]) == []


@pytest.mark.parametrize('summary', ['상속예금 사전접수를 신청했습니다.', '서류 3건이 제출됐습니다.'])
def test_the_summary_always_states_how_many_heirs(summary):
    assert '상속인 3명' in write_brief(facts(), search=rag, llm=Llm(summary=summary))['summary']


def test_a_summary_that_already_states_it_is_left_alone():
    summary = '상속인 3명이 사전접수를 신청했습니다.'
    assert write_brief(facts(), search=rag, llm=Llm(summary=summary))['summary'] == summary


CHECKED = [{'document': 'Heir identity card', 'staffCheck': 'SUPPLEMENT_REQUIRED'}, {'document': 'Decedent basic certificate', 'staffCheck': 'NOT_REVIEWED'}, {'document': 'Decedent detailed family relation certificate', 'staffCheck': 'CONFIRMED'}]


@pytest.mark.parametrize('llm', [None, Llm()], ids=['rule', 'llm'])
def test_staff_lines_use_korean_document_names(llm):
    result = write_brief(facts(staffDocumentChecks=CHECKED), search=rag, llm=llm)
    text = ' '.join(result['staffChecks'] + result['confirmed'])
    assert 'Heir identity card' not in text and 'certificate' not in text
    assert '보완 필요(직원 판단): 상속인 신분증' in result['staffChecks']


def test_the_model_receives_korean_names_and_internal_codes_stay():
    llm = Llm()
    write_brief(facts(staffDocumentChecks=CHECKED), search=rag, llm=llm)
    sent = json.loads(llm.sent[0])['facts']
    assert 'Heir identity card' not in llm.sent[0]
    assert sent['documents'][2]['code'] == 'HEIR_IDENTITY_CARD'


MISSING = (('HEIR_IDENTITY_CARD', 'Heir identity card', 'REQUIRED', False), ('PASSBOOK_AND_SEAL', 'Passbook and seal', 'VISIT_ONLY', False))


@pytest.mark.parametrize('llm', [None, Llm()], ids=['rule', 'llm'])
def test_a_missing_required_document_is_always_a_staff_check(llm):
    checks = write_brief(facts(rows=MISSING), search=rag, llm=llm)['staffChecks']
    assert '미제출 필수 서류: 상속인 신분증' in checks
    assert not any('통장' in line and '미제출' in line for line in checks)


# 2026-11-09 end-to-end run: staff saw "check that EDU-2026.2 was applied" but never what it actually required.
APPROVED = {'sourceVersion': 'EDU-2026.2', 'summary': '위임장 작성일 확인 항목 추가', 'effectiveState': 'SCHEDULED', 'effectiveAt': '2026-11-23T00:00:00Z', 'previousVersion': 'EDU-2026.1',
            'before': '불참 상속인의 위임장은 인감 날인을 확인한다.', 'after': '위임장 작성일이 제출일 기준 30일 이내인지 직원이 함께 확인한다.', 'addedCheckItems': ['위임장 작성일이 제출일 기준 30일 이내인지 확인'],
            'source': {'documentId': 'EDU-DOC-001', 'title': '교육용 가상 업무기준 변경 안내'}, 'appliesTo': {'taskCode': 'INHERITANCE_DEPOSIT', 'caseId': 3, 'reasonCodes': ['DELEGATION_METHOD_MATCH']}}


@pytest.mark.parametrize('llm', [None, Llm()], ids=['rule', 'llm'])
def test_each_added_check_of_an_approved_change_reaches_staff(llm):
    checks = write_brief(facts(absent=1, approvedPolicyChanges=[APPROVED]), search=rag, llm=llm)['staffChecks']
    line = next(text for text in checks if '30일' in text)
    assert 'EDU-2026.2' in line and '시행 예정' in line


def test_the_model_receives_the_change_itself():
    llm = Llm()
    write_brief(facts(absent=1, approvedPolicyChanges=[APPROVED]), search=rag, llm=llm)
    sent = json.loads(llm.sent[0])['facts']['approvedPolicyChanges'][0]
    assert sent['after'] == APPROVED['after'] and sent['addedCheckItems'] == APPROVED['addedCheckItems'] and sent['source']['documentId'] == 'EDU-DOC-001'


def test_a_number_from_the_approved_change_is_grounded():
    summary = '상속인 3명, 승인 변경으로 위임장 작성일 30일 이내 확인이 추가되었습니다.'
    assert write_brief(facts(absent=1, approvedPolicyChanges=[APPROVED]), search=rag, llm=Llm(summary=summary))['summary'] == summary


def test_without_an_approved_change_that_number_is_still_refused():
    summary = '상속인 3명, 위임장 작성일 30일 이내 확인이 추가되었습니다.'
    assert '30일' not in write_brief(facts(absent=1), search=rag, llm=Llm(summary=summary))['summary']


def test_a_change_with_no_added_items_still_names_its_version():
    plain = {**APPROVED, 'addedCheckItems': []}
    assert any('승인된 정책 변경 EDU-2026.2' in line for line in write_brief(facts(absent=1, approvedPolicyChanges=[plain]), search=rag, llm=None)['staffChecks'])
