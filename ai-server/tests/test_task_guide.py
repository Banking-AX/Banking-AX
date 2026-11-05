import json
import unittest

from app.task_guide import build_guide

PASSAGES = [
    {'doc_id': 'C02-01', 'title': '상속예금 해지·지급 FAQ', 'section': '해지 요청 인원 원칙과 예외', 'verified': True,
     'text': '문서: x\n업무: y\n항목: z\n\n- 상속인 전원 영업점 방문이 원칙입니다. 합계 3백만원 이하는 1인 해지가 가능합니다.'},
    {'doc_id': 'C02-01', 'title': '상속예금 해지·지급 FAQ', 'section': '영업점 방문서류', 'verified': True,
     'text': '문서: x\n업무: y\n항목: z\n\n- 가족관계증명서(상세)와 기본증명서를 3개월 이내 발급분으로 준비해 주세요.'},
    {'doc_id': 'C02-09', 'title': '검수 전 문서', 'section': '무엇이든', 'verified': False, 'text': '문서: x\n\n- 검수 전 내용'},
]


def search(_query, _task, _top_k, _audience):
    return PASSAGES


class FakeLlm:
    def __init__(self, payload):
        self.payload = payload

    def summarize(self, query, task_name, passages):
        self.passages = passages
        return self.payload if isinstance(self.payload, str) else json.dumps(self.payload, ensure_ascii=False)


class TaskGuideTests(unittest.TestCase):
    def test_llm_bullets_cite_verified_passages_and_ungrounded_numbers_are_dropped(self) -> None:
        llm = FakeLlm({'understood': '돌아가신 아버님 예금을 찾고 싶으세요.', 'bullets': [
            {'text': '상속인 모두 방문하는 것이 원칙이에요.', 'source': 1},
            {'text': '5백만원까지는 혼자 해지할 수 있어요.', 'source': 1},   # 5 is not in the passage
            {'text': '가족관계증명서는 3개월 이내 발급분이 필요해요.', 'source': 2}]})
        guide = build_guide('아버지 통장 돈 어떻게 찾아요', 'INHERITANCE_DEPOSIT', search=search, llm=llm)
        self.assertEqual(guide['officialName'], '상속예금 해지 및 지급 사전접수')
        self.assertEqual(guide['summaryMethod'], 'LLM')
        self.assertEqual([b['text'] for b in guide['bullets']], ['상속인 모두 방문하는 것이 원칙이에요.', '가족관계증명서는 3개월 이내 발급분이 필요해요.'])
        self.assertEqual([c['section'] for c in guide['citations']], ['해지 요청 인원 원칙과 예외', '영업점 방문서류'])
        self.assertEqual(len(llm.passages), 2, 'unverified passage must never reach the LLM')
        self.assertEqual([s['key'] for s in guide['steps']], ['RETRIEVE', 'SUMMARIZE'])

    def test_without_llm_the_guide_uses_passage_extracts(self) -> None:
        guide = build_guide('상속예금', 'INHERITANCE_DEPOSIT', search=search, llm=None)
        self.assertEqual(guide['summaryMethod'], 'EXTRACTIVE')
        self.assertIsNone(guide['understood'])
        self.assertTrue(guide['bullets'][0]['text'].startswith('상속인 전원 영업점 방문이 원칙입니다.'))

    def test_invalid_llm_output_falls_back_to_extracts(self) -> None:
        guide = build_guide('상속예금', 'INHERITANCE_DEPOSIT', search=search, llm=FakeLlm('not json'))
        self.assertEqual(guide['summaryMethod'], 'EXTRACTIVE')
        self.assertTrue(guide['bullets'])

    def test_only_unverified_evidence_gives_no_summary(self) -> None:
        guide = build_guide('상속예금', 'INHERITANCE_DEPOSIT', search=lambda *_: [PASSAGES[2]], llm=None)
        self.assertEqual(guide['bullets'], [])
        self.assertEqual(guide['steps'][0]['status'], 'NO_EVIDENCE')

    def test_vague_sentence_is_classified_first_and_no_match_asks_back(self) -> None:
        matched = build_guide('아버지 통장 돈', None, search=search, llm=None, classify=lambda _q: 'INHERITANCE_DEPOSIT')
        self.assertEqual(matched['steps'][0], {'key': 'CLASSIFY', 'label': '6개 업무 중 해당 업무 찾기', 'status': 'DONE', 'detail': '상속예금 해지 및 지급 사전접수'})
        unmatched = build_guide('그냥 궁금해서요', None, search=search, llm=None, classify=lambda _q: None)
        self.assertFalse(unmatched['matched'])
        self.assertTrue(unmatched['clarification'])

    def test_lookup_tasks_have_a_profile_but_no_retrieval(self) -> None:
        guide = build_guide('지난달 입금 내역', 'TRANSACTION_SEARCH', search=lambda *_: self.fail('A tasks have no RAG documents'), llm=None)
        self.assertEqual(guide['kind'], 'A · 바로 조회')
        self.assertEqual(guide['steps'], [])

    def test_curated_index_returns_a_c02_guide(self) -> None:
        from app.retrieve import search_with_strategy
        guide = build_guide('아버지가 돌아가셨는데 통장 돈 어떻게 찾아요', 'INHERITANCE_DEPOSIT', llm=None,
                            search=lambda q, t, k, a: search_with_strategy(q, t, k, a, 'keyword'))
        self.assertTrue(guide['bullets'])
        self.assertTrue(all(c['docId'].startswith('C02') for c in guide['citations']))


if __name__ == '__main__':
    unittest.main()
