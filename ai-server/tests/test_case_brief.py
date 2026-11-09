import json

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.staff_agent.case_brief import write_brief
from app.staff_agent.router import create_router

FACTS = {
    'caseStatus': 'SUBMITTED', 'revision': 6,
    'people': {'heirCount': 2, 'absentCount': 1, 'delegation': {'DOCUMENT': 1}},
    'documents': [{'name': '위임장', 'submitted': True, 'ocrStatus': 'EXTRACTED'}, {'name': '기본증명서', 'submitted': True, 'ocrStatus': 'NOT_APPLICABLE'}],
    'verification': {'ocrStatus': 'EXTRACTED', 'documentType': 'TYPE_MATCHED', 'matched': ['예금주 성명', '대리인 성명'], 'mismatched': [], 'staffRequired': ['계좌번호'], 'notCompared': ['주소']},
    'staffMustConfirm': ['서류 원본', '실제 위임 의사'],
}


def rag(*_):
    return [{'chunk_id': 'c1', 'doc_id': 'C02-01', 'title': '상속예금 FAQ', 'section': '위임서류', 'text': '인감증명서 본인발급용', 'verified': True},
            {'chunk_id': 'c2', 'doc_id': 'X', 'title': '미검수', 'section': '-', 'text': '미검수', 'verified': False}]


class StubLlm:
    def __init__(self, output):
        self.output, self.sent = output, None

    def write(self, facts):
        self.sent = facts
        return self.output if isinstance(self.output, str) else json.dumps(self.output, ensure_ascii=False)


def brief(**overrides):
    return {'summary': '상속예금 사전접수이며 서류가 제출됐습니다.', 'confirmed': ['위임장의 예금주 성명, 대리인 성명이 신청정보와 일치합니다.'],
            'staffChecks': ['계좌번호를 은행 기준정보로 확인해 주세요.'], 'basis': [{'docId': 'C02-01', 'reason': '위임서류 안내'}],
            'customerDraft': '서류가 접수됐어요. 담당 직원이 확인한 뒤 결과를 안내드릴게요.', **overrides}


def facts(**verification):
    return {**FACTS, 'verification': {**FACTS['verification'], **verification}}


def test_llm_result_is_kept_and_marked():
    result = write_brief(FACTS, search=rag, llm=StubLlm(brief()))

    assert result['method'] == 'LLM' and result['promptVersion'] == 'case-brief-7'
    assert result['confirmed'] == ['위임장의 예금주 성명, 대리인 성명이 신청정보와 일치합니다.']
    assert [row['docId'] for row in result['evidence']] == ['C02-01']


def test_rule_fallback_never_claims_ai():
    result = write_brief(FACTS, search=rag, llm=None)

    assert result['method'] == 'RULE_FALLBACK' and result['model'] is None
    assert '예금주 성명, 대리인 성명' in result['confirmed'][0]
    assert any('계좌번호' in line for line in result['staffChecks'])


def test_provider_or_schema_failure_uses_the_rules():
    assert write_brief(FACTS, search=rag, llm=StubLlm('not json'))['method'] == 'RULE_FALLBACK'
    assert write_brief(FACTS, search=rag, llm=StubLlm({'summary': 'x'}))['method'] == 'RULE_FALLBACK'


def test_no_match_produces_no_confirmed_line_even_if_the_model_invents_one():
    result = write_brief(facts(matched=[], ocrStatus='NOT_RUN'), search=rag, llm=StubLlm(brief()))

    assert result['confirmed'] == []


def test_unmatched_fields_cannot_be_called_confirmed():
    output = brief(confirmed=['위임장의 예금주 성명이 일치합니다.', '생년월일도 일치합니다.', '서명과 인감도 확인했습니다.'])

    assert write_brief(FACTS, search=rag, llm=StubLlm(output))['confirmed'] == ['위임장의 예금주 성명이 일치합니다.']


def test_a_decision_in_the_draft_is_replaced_by_the_rule_text():
    result = write_brief(FACTS, search=rag, llm=StubLlm(brief(customerDraft='서류가 승인되어 지급 가능합니다.')))

    assert '승인' not in result['customerDraft'] and '담당 직원이 확인' in result['customerDraft']
    assert result['method'] == 'LLM'


def test_a_decision_in_the_summary_is_replaced():
    result = write_brief(FACTS, search=rag, llm=StubLlm(brief(summary='서류 이상 없음으로 진행 가능합니다.')))

    assert '진행 가능' not in result['summary']


def test_invented_numbers_and_foreign_basis_are_removed():
    output = brief(staffChecks=['인감증명서 77일 이내 확인', '계좌번호 확인'], basis=[{'docId': 'C02-01', 'reason': '위임서류'}, {'docId': 'MADE-UP', 'reason': '추측'}])
    result = write_brief(FACTS, search=rag, llm=StubLlm(output))

    assert result['staffChecks'] == ['계좌번호 확인', '직접 확인: 서류 원본', '직접 확인: 실제 위임 의사']
    assert [row['docId'] for row in result['basis']] == ['C02-01']


def test_missing_rag_evidence_is_reported_without_rules():
    def broken(*_):
        raise RuntimeError('index down')

    result = write_brief(FACTS, search=broken, llm=None)
    assert result['basisMissing'] is True and result['basis'] == [] and result['evidence'] == []


def test_staff_only_checks_are_kept_when_the_model_leaves_them_out():
    result = write_brief(FACTS, search=rag, llm=StubLlm(brief(staffChecks=['계좌번호 확인'])))

    assert result['staffChecks'] == ['계좌번호 확인', '직접 확인: 서류 원본', '직접 확인: 실제 위임 의사']


def test_an_approved_change_is_always_a_staff_check_even_without_the_model():
    approved = {**FACTS, 'approvedPolicyChanges': [{'sourceVersion': 'v2', 'summary': '위임서류 변경', 'effectiveState': 'CURRENT'}]}

    for llm in (StubLlm(brief()), None):
        checks = write_brief(approved, search=rag, llm=llm)['staffChecks']
        assert any('승인된 정책 변경 v2' in line for line in checks)


def test_unreadable_notices_are_reported_instead_of_implying_there_are_none():
    checks = write_brief({**FACTS, 'policyNoticesUnavailable': True}, search=rag, llm=None)['staffChecks']

    assert any('공지를 불러오지 못했습니다' in line for line in checks)


def test_no_notices_adds_no_policy_line():
    assert not any('정책' in line for line in write_brief({**FACTS, 'approvedPolicyChanges': []}, search=rag, llm=None)['staffChecks'])


STAFF_CHECKS = {**FACTS, 'staffDocumentChecks': [
    {'document': '가족관계증명서(상세)', 'staffCheck': 'CONFIRMED'},
    {'document': '인감증명서', 'staffCheck': 'SUPPLEMENT_REQUIRED'},
    {'document': '기본증명서', 'staffCheck': 'NOT_REVIEWED'}]}


def test_staff_checks_are_listed_from_the_record_not_the_model():
    for llm in (StubLlm(brief()), None):
        result = write_brief(STAFF_CHECKS, search=rag, llm=llm)
        assert '직원 확인: 가족관계증명서(상세) 원본 확인 완료' in result['confirmed']
        assert '보완 필요(직원 판단): 인감증명서' in result['staffChecks']
        assert '직원 미확인: 기본증명서' in result['staffChecks']


def test_the_model_cannot_claim_a_staff_check_that_was_not_recorded():
    output = brief(confirmed=['위임장의 예금주 성명이 일치합니다.', '기본증명서 원본을 확인했습니다.'])
    result = write_brief(STAFF_CHECKS, search=rag, llm=StubLlm(output))
    assert not any('기본증명서' in line and '확인' in line and '미확인' not in line for line in result['confirmed'])


def test_clarification_states_become_staff_checks_without_customer_words():
    facts = {**FACTS, 'customerClarifications': [{'kind': 'VISIT_ATTENDANCE', 'status': 'EXPIRED'}, {'kind': 'DOCUMENT', 'status': 'ANSWERED'}]}
    checks = write_brief(facts, search=rag, llm=None)['staffChecks']
    assert '고객 미응답(잠정 처리): 방문 여부' in checks and '고객 답변 원문 확인: 서류' in checks


def test_no_staff_checks_means_no_staff_lines():
    result = write_brief(FACTS, search=rag, llm=None)
    assert not any(line.startswith('직원 확인:') for line in result['confirmed'])


def test_only_settled_facts_reach_the_model():
    llm = StubLlm(brief())
    write_brief(FACTS, search=rag, llm=llm)

    assert '미검수' not in llm.sent and 'fileName' not in llm.sent


def test_endpoint_requires_the_internal_token(monkeypatch):
    monkeypatch.setenv('STAFF_AGENT_GATEWAY_TOKEN', 'agent-token')
    monkeypatch.delenv('OPENAI_API_KEY', raising=False)
    app = FastAPI()
    app.include_router(create_router(), prefix='/api/v1')
    client = TestClient(app)

    assert client.post('/api/v1/staff-agent/case-brief', json={'facts': FACTS}).status_code == 403
    assert client.post('/api/v1/staff-agent/case-brief', json={}, headers={'X-Staff-Agent-Token': 'agent-token'}).status_code == 422
    response = client.post('/api/v1/staff-agent/case-brief', json={'facts': FACTS}, headers={'X-Staff-Agent-Token': 'agent-token'})
    assert response.status_code == 200 and response.json()['method'] == 'RULE_FALLBACK'
