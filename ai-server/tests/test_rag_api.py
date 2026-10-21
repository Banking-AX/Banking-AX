from __future__ import annotations

import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app


SAMPLE_RESULT = {
    'chunk_id': 'chunk-1', 'doc_id': 'B01-01', 'title': '채무변제충당순서 변경 신청서',
    'section': '양식 기재 항목', 'text': '원본 검색 청크', 'score': 0.123456,
    'source_file': 'knowledge/sources/B01-01.pdf', 'source_pages': [1], 'verified': False,
    'context_text': '원본 검색 청크\n\n--- 인접 청크 ---\n\n인접 원문 청크',
    'context_chunk_ids': ['chunk-1', 'chunk-2'], 'context_sections': ['양식 기재 항목', '양식 기재 항목'],
}
REQUEST = {'query': '충당순서는 어떻게 변경하나요?', 'task_code': 'REPAYMENT_ORDER_CHANGE', 'audience': 'customer', 'top_k': 3}


class RagApiContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.client = TestClient(app)

    @patch('app.main._run_search', return_value=[SAMPLE_RESULT])
    def test_search_preserves_anchor_and_context_contract(self, _: object) -> None:
        response = self.client.post('/api/v1/rag/search', json=REQUEST)
        self.assertEqual(response.status_code, 200)
        result = response.json()['results'][0]
        for field in ('chunk_id', 'doc_id', 'title', 'section', 'text', 'score', 'source_file', 'source_pages', 'verified',
                      'context_text', 'context_chunk_ids', 'context_sections'):
            self.assertIn(field, result)
        self.assertEqual(result['text'], SAMPLE_RESULT['text'])
        self.assertEqual(result['context_text'], SAMPLE_RESULT['context_text'])
        self.assertIn(result['text'], result['context_text'])
        self.assertEqual(result['context_chunk_ids'], SAMPLE_RESULT['context_chunk_ids'])
        self.assertEqual(result['context_sections'], SAMPLE_RESULT['context_sections'])

    @patch('app.main._run_search', return_value=[])
    def test_empty_search_is_a_normal_empty_result(self, _: object) -> None:
        response = self.client.post('/api/v1/rag/search', json=REQUEST)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['results'], [])

    def test_openapi_declares_context_and_error_models(self) -> None:
        schema = self.client.get('/openapi.json').json()
        response_schema = schema['paths']['/api/v1/rag/search']['post']['responses']['200']['content']['application/json']['schema']
        self.assertEqual(response_schema['$ref'], '#/components/schemas/SearchResponse')
        properties = schema['components']['schemas']['SearchResult']['properties']
        for field in ('context_text', 'context_chunk_ids', 'context_sections'):
            self.assertIn(field, properties)
        self.assertIn('ErrorResponse', schema['components']['schemas'])

    def test_invalid_request_has_machine_readable_error(self) -> None:
        invalid_requests = [
            {'query': '', 'task_code': 'NOT_A_TASK'},
            {**REQUEST, 'audience': 'operator'},
            {**REQUEST, 'top_k': 0},
            {**REQUEST, 'top_k': 9},
        ]
        for body in invalid_requests:
            with self.subTest(body=body):
                response = self.client.post('/api/v1/rag/search', json=body)
                self.assertEqual(response.status_code, 422)
                self.assertEqual(response.json()['error']['code'], 'INVALID_REQUEST')
                self.assertFalse(response.json()['error']['retryable'])

    @patch('app.main._run_search', side_effect=TimeoutError())
    def test_timeout_error(self, _: object) -> None:
        response = self.client.post('/api/v1/rag/search', json=REQUEST)
        self.assertEqual(response.status_code, 504)
        self.assertEqual(response.json()['error']['code'], 'RETRIEVAL_TIMEOUT')
        self.assertTrue(response.json()['error']['retryable'])

    @patch('app.main._run_search', side_effect=RuntimeError('chromadb collection unavailable'))
    def test_vector_backend_error(self, _: object) -> None:
        response = self.client.post('/api/v1/rag/search', json=REQUEST)
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()['error']['code'], 'VECTOR_DB_UNAVAILABLE')

    @patch('app.main._run_search', side_effect=RuntimeError('embedding model unavailable'))
    def test_embedding_model_error(self, _: object) -> None:
        response = self.client.post('/api/v1/rag/search', json=REQUEST)
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()['error']['code'], 'EMBEDDING_MODEL_ERROR')

    @patch('app.main._run_search', side_effect=RuntimeError('unexpected failure'))
    def test_internal_error_hides_exception_detail(self, _: object) -> None:
        response = self.client.post('/api/v1/rag/search', json=REQUEST)
        self.assertEqual(response.status_code, 500)
        body = response.json()
        self.assertEqual(body['error']['code'], 'INTERNAL_ERROR')
        self.assertNotIn('unexpected failure', body['error']['message'])


if __name__ == '__main__':
    unittest.main()
