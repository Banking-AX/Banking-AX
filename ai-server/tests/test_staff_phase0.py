from __future__ import annotations

import unittest
from unittest.mock import patch

from app.core.staff_engine import (
    CheckRegistry, CheckRequest, FakeCasePort, FakeDocumentStore, FakeNotificationPort,
    FakeToolGateway, FakeVerificationPort, FakeVisitPort, StaffCheckExecutor,
)
from app.core.staff_engine.fakes import FakeCase, PortError
from app.core.staff_engine.rag_adapter import RagEvidenceAdapter, existing_rag_search
from app.tasks.c02 import register_c02_check
from app.tasks.c02 import InheritanceDepositTask


def verified_search(_: str, __: str, ___: int, ____: str) -> list[dict]:
    return [{
        'doc_id': 'C02-02', 'title': '위임장(상속) 양식', 'section': '본문',
        'source_pages': [1], 'verified': True, 'text': '검수된 근거 예시',
    }]


def unverified_search(_: str, __: str, ___: int, ____: str) -> list[dict]:
    return [{
        'doc_id': 'C02-01', 'title': '상속예금 FAQ', 'section': '방문서류',
        'source_pages': [], 'verified': False, 'text': 'PoC 근거 예시',
    }]


class StaffPhaseZeroTests(unittest.TestCase):
    def _executor(self, search=verified_search) -> StaffCheckExecutor:
        registry = CheckRegistry()
        register_c02_check(registry)
        cases = [FakeCase(case_id=200, revision=1, task_code='INHERITANCE_DEPOSIT')]
        return StaffCheckExecutor(FakeCasePort(cases), registry, RagEvidenceAdapter(search))

    def test_c02_fake_check_preserves_policy_evidence_without_final_decision(self) -> None:
        response = self._executor().run(CheckRequest(
            request_id='c02-200-r1', case_id=200, revision=1, task_code='INHERITANCE_DEPOSIT',
            rag_query='상속 위임장 준비 서류',
            case_snapshot={'heirs': [{'visitStatus': 'UNCONFIRMED_ABSENT'}]},
        ))
        self.assertEqual(response.status, 'SUCCEEDED')
        self.assertEqual(response.review_status, 'READY_FOR_STAFF')
        self.assertTrue(response.policy_evidence)
        self.assertNotIn('PROCEED', {item.result for item in response.review_items})
        self.assertIn('NEEDS_STAFF_REVIEW', {item.result for item in response.review_items})
        item = response.as_dict()['reviewItems'][0]
        self.assertIn('basisDocId', item)
        self.assertIn('documentFileId', item)
        self.assertNotIn('basis_doc_id', item)

    def test_unverified_rag_never_turns_into_a_pass(self) -> None:
        response = self._executor(unverified_search).run(CheckRequest(
            request_id='c02-unverified', case_id=200, revision=1, task_code='INHERITANCE_DEPOSIT',
            rag_query='상속예금 방문',
        ))
        self.assertTrue(any(item.evidence.get('errorCode') == 'RAG_UNVERIFIED' for item in response.review_items))
        self.assertNotIn('PASS', {item.result for item in response.review_items})

    def test_c02_rule_and_missing_document_snapshot_become_staff_items(self) -> None:
        response = self._executor().run(CheckRequest(
            request_id='c02-rules', case_id=200, revision=1, task_code='INHERITANCE_DEPOSIT',
            case_snapshot={'decedent': {}, 'heirs': [], 'outOfScope': ['MINOR_HEIR'], 'missingDocuments': ['HEIR_IDENTITY_CARD:h1']},
        ))
        categories = {item.category for item in response.review_items}
        self.assertTrue({'REQUIRED_INPUT', 'SCOPE', 'DOCUMENT_SLOT'} <= categories)

    def test_c02_customer_task_builds_structured_payload_without_submission(self) -> None:
        class Gateway:
            def validate_c02_intake(self, request): return {'accepted': False, 'echo': request['decedent']}
        response = InheritanceDepositTask(Gateway()).run({'requestId': 'c02-customer', 'workItemId': 1, 'customerId': 2, 'decedent': {'deathDate': '2020-01-01'}, 'heirs': []})
        self.assertEqual(response['taskCode'], 'INHERITANCE_DEPOSIT')
        self.assertFalse(response['submitted'])

    @patch('app.retrieve.search', return_value=[{
        'doc_id': 'C02-02', 'title': '위임장(상속) 양식', 'section': '본문',
        'source_pages': [1], 'verified': True, 'text': '기존 RAG 반환값',
    }])
    def test_existing_rag_function_boundary_preserves_citation_fields(self, _: object) -> None:
        evidence = RagEvidenceAdapter(existing_rag_search).search('위임장', 'INHERITANCE_DEPOSIT')
        self.assertEqual(evidence[0].doc_id, 'C02-02')
        self.assertEqual(evidence[0].section, '본문')
        self.assertEqual(evidence[0].source_pages, [1])
        self.assertTrue(evidence[0].verified)

    def test_duplicate_and_stale_requests_are_not_accepted_as_new_runs(self) -> None:
        executor = self._executor()
        request = CheckRequest(request_id='same-id', case_id=200, revision=1, task_code='INHERITANCE_DEPOSIT')
        self.assertEqual(executor.run(request), executor.run(request))
        collision = executor.run(CheckRequest(request_id='same-id', case_id=200, revision=1, task_code='INHERITANCE_DEPOSIT', rag_query='다른 요청'))
        self.assertEqual(collision.error['code'], 'DUPLICATE_REQUEST_CONFLICT')
        stale = executor.run(CheckRequest(request_id='stale', case_id=200, revision=2, task_code='INHERITANCE_DEPOSIT'))
        self.assertEqual(stale.error['code'], 'STALE_REVISION')

    def test_check_error_and_stale_case_do_not_progress_to_later_nodes(self) -> None:
        called = []
        registry = CheckRegistry()

        def failing_check(_: CheckRequest):
            called.append('handler')
            raise RuntimeError('fixture failure')

        registry.register('INHERITANCE_DEPOSIT', 'STAFF_PRECHECK', failing_check)
        executor = StaffCheckExecutor(
            FakeCasePort([FakeCase(case_id=200, revision=1, task_code='INHERITANCE_DEPOSIT')]),
            registry, RagEvidenceAdapter(verified_search),
        )
        self.assertTrue(executor._graph.__class__.__module__.startswith('langgraph'))

        failed = executor.run(CheckRequest(request_id='handler-error', case_id=200, revision=1, task_code='INHERITANCE_DEPOSIT'))
        self.assertEqual(failed.error['code'], 'CHECK_FAILED')
        self.assertEqual(failed.review_status, 'FAILED_NEEDS_STAFF')

        stale = executor.run(CheckRequest(request_id='stale-no-handler', case_id=200, revision=2, task_code='INHERITANCE_DEPOSIT'))
        self.assertEqual(stale.error['code'], 'STALE_REVISION')
        self.assertEqual(called, ['handler'])

    def test_document_notification_and_visit_fakes_obey_contract_edges(self) -> None:
        documents = FakeDocumentStore()
        first = documents.register(501, 'application/pdf', 10)
        second = documents.register(501, 'image/png', 20)
        self.assertEqual((first['version'], documents.latest(501)['version']), (1, 2))
        with self.assertRaisesRegex(PortError, 'FILE_TOO_LARGE'):
            documents.register(501, 'application/pdf', 10 * 1024 * 1024 + 1)

        notifications = FakeNotificationPort()
        event = {'dedupKey': 'case:200:result:1', 'eventId': 'evt-1'}
        self.assertEqual(notifications.publish(event), notifications.publish(event))
        self.assertEqual(len(notifications.events), 1)

        self.assertEqual(FakeToolGateway({'ruleResults': ['fixture']}).validate({})['ruleResults'], ['fixture'])
        self.assertTrue(FakeVerificationPort(accepted=True).verify_submission(100, 'C02_SUBMIT'))
        self.assertFalse(FakeVerificationPort(accepted=False).verify_submission(100, 'C02_SUBMIT'))

        visits = FakeVisitPort()
        with self.assertRaisesRegex(PortError, 'RESERVATION_NOT_ALLOWED'):
            visits.request(case_review_passed=False, result_notified=False, case_id=200, branch_slot_id=701, request_id='visit-1')
        self.assertEqual(visits.request(case_review_passed=True, result_notified=True, case_id=200, branch_slot_id=701, request_id='visit-2')['status'], 'SELECTED')


if __name__ == '__main__':
    unittest.main()
