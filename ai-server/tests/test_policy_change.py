import hashlib
import hmac
import json

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.mock_bank_policy.router import SCENARIOS, PolicyFeed, create_router
from app.staff_agent.policy_impact import analyze, source_diff
from app.staff_agent.router import create_router as create_staff_router

TOKEN = {'X-Policy-Source-Token': 'source-token'}


def request(**overrides):
    change = {'changeSummary': '[교육용 가상 사례] 위임장 작성일 확인 추가',
              'detail': {'before': '인감 날인을 확인한다.', 'after': '인감 날인과 작성일 30일 이내를 확인한다.',
                         'addedCheckItems': ['작성일 30일 이내 확인']}}
    return {'change': change, 'mappedTaskCodes': ['INHERITANCE_DEPOSIT'],
            'registeredTasks': ['INHERITANCE_DEPOSIT', 'DEBT_ADJUSTMENT'],
            'caseCandidates': [{'caseId': 2, 'revision': 1, 'reasonCodes': ['DELEGATION_METHOD_MATCH']}], **overrides}


def rag(*_):
    return [{'chunk_id': 'c1', 'doc_id': 'C02-01', 'title': '상속예금', 'section': '위임', 'text': '위임장 안내', 'verified': True},
            {'chunk_id': 'c2', 'doc_id': 'X', 'title': '미검수', 'section': '-', 'text': '미검수 자료', 'verified': False}]


class StubLlm:
    def __init__(self, output):
        self.output = output

    def interpret(self, facts):
        return self.output if isinstance(self.output, str) else json.dumps(self.output, ensure_ascii=False)


def llm_output(**overrides):
    return {'summary': '위임장 작성일 확인이 추가됩니다.', 'changePoints': [{'text': '작성일 30일 확인 추가'}],
            'relatedTasks': [{'taskCode': 'INHERITANCE_DEPOSIT', 'reason': '위임장 관련'}],
            'recheckItems': [{'text': '작성일 확인', 'caseIds': [2]}], 'customerNoticeHint': '', **overrides}


def mock_client(monkeypatch):
    monkeypatch.setenv('POLICY_SOURCE_TOKEN', 'source-token')
    monkeypatch.setenv('POLICY_SOURCE_SIGNING_SECRET', 'signing-secret')
    app = FastAPI()
    app.include_router(create_router(PolicyFeed()))
    return TestClient(app)


def test_feed_is_signed_and_rejects_a_missing_token(monkeypatch):
    client = mock_client(monkeypatch)
    assert client.get('/mock-bank/v1/policy-changes').status_code == 403
    assert client.post('/mock-bank/v1/admin/scenarios/c02-poa-v2', headers=TOKEN).status_code == 200

    response = client.get('/mock-bank/v1/policy-changes', headers=TOKEN)

    expected = hmac.new(b'signing-secret', response.content, hashlib.sha256).hexdigest()
    assert response.headers['X-Policy-Signature'] == f'sha256={expected}'
    assert response.json()['events'][0]['detail']['fictional'] is True


def test_cursor_returns_only_newer_events_and_replay_keeps_the_body(monkeypatch):
    client = mock_client(monkeypatch)
    client.post('/mock-bank/v1/admin/scenarios/b02-info', headers=TOKEN)
    first = client.get('/mock-bank/v1/policy-changes', headers=TOKEN).json()
    client.post('/mock-bank/v1/admin/replay-last', headers=TOKEN)

    second = client.get(f"/mock-bank/v1/policy-changes?after={first['nextCursor']}", headers=TOKEN).json()

    assert len(second['events']) == 1
    assert {**second['events'][0], 'seq': 0} == {**first['events'][0], 'seq': 0}


def test_a_tag_keeps_rehearsal_ids_apart_from_the_demo_ids(monkeypatch):
    client = mock_client(monkeypatch)
    client.post('/mock-bank/v1/admin/scenarios/c02-poa-v2?tag=RUN1', headers=TOKEN)

    event = client.get('/mock-bank/v1/policy-changes', headers=TOKEN).json()['events'][0]

    assert event['changeEventId'].endswith('-RUN1') and event['externalPolicyId'].endswith('-RUN1')
    assert client.post('/mock-bank/v1/admin/scenarios/c02-poa-v2?tag=bad!', headers=TOKEN).status_code == 422


def test_every_scenario_is_marked_fictional():
    for events in SCENARIOS.values():
        for event in events:
            assert event['detail']['fictional'] is True and event['changeSummary'].startswith('[교육용 가상 사례]')


def test_fallback_uses_only_source_facts_and_marks_the_method():
    result = analyze(request(), search=rag, llm=None)

    assert result['method'] == 'RULE_FALLBACK' and result['model'] is None
    assert result['recheckItems'] == [{'text': '작성일 30일 이내 확인', 'caseIds': [2]}]
    assert [row['docId'] for row in result['evidence']] == ['C02-01']


def test_llm_output_cannot_invent_tasks_cases_or_numbers():
    llm = StubLlm(llm_output(
        relatedTasks=[{'taskCode': 'INHERITANCE_DEPOSIT', 'reason': '위임장 관련'}, {'taskCode': 'MADE_UP', 'reason': '추측'}],
        recheckItems=[{'text': '작성일 확인', 'caseIds': [2, 99]}, {'text': '77일 이내 확인', 'caseIds': [2]}]))

    result = analyze(request(), search=rag, llm=llm)

    assert result['method'] == 'LLM'
    assert [row['taskCode'] for row in result['relatedTasks']] == ['INHERITANCE_DEPOSIT']
    assert result['recheckItems'] == [{'text': '작성일 확인', 'caseIds': [2]}]


def test_invalid_llm_output_falls_back():
    assert analyze(request(), search=rag, llm=StubLlm('not json'))['method'] == 'RULE_FALLBACK'
    assert analyze(request(), search=rag, llm=StubLlm(llm_output(summary='기간이 77일로 바뀝니다.')))['method'] == 'RULE_FALLBACK'


def test_rag_failure_keeps_the_analysis_alive():
    def broken(*_):
        raise RuntimeError('index down')

    result = analyze(request(), search=broken, llm=None)
    assert result['evidence'] == [] and result['method'] == 'RULE_FALLBACK'


def test_tasks_without_a_rag_index_are_still_analyzed():
    called = []
    result = analyze(request(mappedTaskCodes=['FX_REMITTANCE'], registeredTasks=['FX_REMITTANCE']),
                     search=lambda *args: called.append(args) or [], llm=None)
    assert called == [] and result['relatedTasks'][0]['taskCode'] == 'FX_REMITTANCE'


def test_source_diff_keeps_whole_words():
    assert source_diff('예상 이자를 안내한다.', '예상 이자와 불이익을 함께 안내한다.') == ['이자를 → 이자와 불이익을 함께']
    assert source_diff('날인 확인', '날인 확인 30일') == ['추가: 30일']
    assert source_diff('', '') == []


def test_a_keyword_echo_is_not_shown_as_a_customer_hint():
    assert analyze(request(), search=rag, llm=StubLlm(llm_output(customerNoticeHint='가능성')))['customerNoticeHint'] == ''
    hint = '고객에게 작성일 확인을 안내할 수 있습니다.'
    assert analyze(request(), search=rag, llm=StubLlm(llm_output(customerNoticeHint=hint)))['customerNoticeHint'] == hint


def test_policy_impact_endpoint_requires_the_internal_token(monkeypatch):
    monkeypatch.setenv('STAFF_AGENT_GATEWAY_TOKEN', 'agent-token')
    monkeypatch.delenv('OPENAI_API_KEY', raising=False)
    app = FastAPI()
    app.include_router(create_staff_router(), prefix='/api/v1')
    client = TestClient(app)

    assert client.post('/api/v1/staff-agent/policy-impact', json=request()).status_code == 403
    assert client.post('/api/v1/staff-agent/policy-impact', json={}, headers={'X-Staff-Agent-Token': 'agent-token'}).status_code == 422
    response = client.post('/api/v1/staff-agent/policy-impact', json=request(), headers={'X-Staff-Agent-Token': 'agent-token'})
    assert response.status_code == 200 and response.json()['method'] == 'RULE_FALLBACK'
