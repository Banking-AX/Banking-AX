from __future__ import annotations

import json

from app.staff_agent.policy_question import NO_EVIDENCE, answer_question


ROWS = [
    {'chunk_id': 'a', 'doc_id': 'DOC-A', 'title': '상속예금 안내', 'section': '위임서류', 'text': '불참 상속인은 위임장과 3개월 이내 인감증명서를 제출합니다.', 'verified': True, 'trust_level': 'OFFICIAL_SHINHAN'},
    {'chunk_id': 'b', 'doc_id': 'DOC-B', 'title': '미검수 자료', 'section': '기타', 'text': '검수되지 않은 내용입니다.', 'verified': False, 'trust_level': 'UNKNOWN_SOURCE'},
]


def search(*_):
    return ROWS


class Llm:
    def __init__(self, **output):
        self.output = output
        self.prompts = []

    def write(self, facts: str) -> str:
        self.prompts.append(facts)
        return json.dumps(self.output, ensure_ascii=False)


class BrokenLlm:
    def write(self, facts: str) -> str:
        raise RuntimeError('provider down')


def ask(llm, rows=search):
    return answer_question('인감증명서 기한은?', 'INHERITANCE_DEPOSIT', search=rows, llm=llm)


def test_answer_cites_only_the_documents_it_used():
    result = ask(Llm(sufficient=True, answer='인감증명서는 3개월 이내 발급분이에요.', usedDocIds=['DOC-A', 'NOT-RETRIEVED']))
    assert result['status'] == 'ANSWERED' and result['method'] == 'LLM'
    assert [row['docId'] for row in result['citations']] == ['DOC-A']
    assert result['citations'][0]['trustLevel'] == 'OFFICIAL_SHINHAN'
    assert result['promptVersion'] == 'policy-question-2' and result['retrievedAt']


def test_unverified_documents_never_reach_the_model():
    llm = Llm(sufficient=True, answer='인감증명서는 3개월 이내 발급분이에요.', usedDocIds=['DOC-A'])
    ask(llm)
    assert '미검수 자료' not in llm.prompts[0]


def test_no_evidence_does_not_call_the_model():
    llm = Llm(sufficient=True, answer='아무 답', usedDocIds=[])
    result = ask(llm, rows=lambda *_: [])
    assert result['status'] == 'INSUFFICIENT' and result['answer'] == NO_EVIDENCE and result['citations'] == []
    assert llm.prompts == []


def test_a_search_failure_is_reported_as_no_evidence():
    def broken(*_):
        raise RuntimeError('index down')
    assert ask(Llm(sufficient=True, answer='답', usedDocIds=[]), rows=broken)['status'] == 'INSUFFICIENT'


def test_model_saying_evidence_is_short_is_not_shown_as_an_answer():
    result = ask(Llm(sufficient=False, answer='아마 될 거예요.', usedDocIds=['DOC-A']))
    assert result['status'] == 'INSUFFICIENT' and '아마' not in result['answer']


def test_invented_numbers_fall_back_to_the_evidence():
    result = ask(Llm(sufficient=True, answer='인감증명서는 6개월 이내 발급분이에요.', usedDocIds=['DOC-A']))
    assert result['status'] == 'EVIDENCE_ONLY' and '6개월' not in result['answer']


def test_a_decision_about_a_case_falls_back_to_the_evidence():
    result = ask(Llm(sufficient=True, answer='이 고객은 지급 가능합니다.', usedDocIds=['DOC-A']))
    assert result['status'] == 'EVIDENCE_ONLY'


def test_provider_failure_keeps_the_evidence():
    result = ask(BrokenLlm())
    assert result['status'] == 'EVIDENCE_ONLY' and result['method'] == 'RULE_FALLBACK' and result['citations'][0]['docId'] == 'DOC-A'


def test_without_a_key_only_evidence_is_returned():
    assert ask(None)['status'] == 'EVIDENCE_ONLY'
