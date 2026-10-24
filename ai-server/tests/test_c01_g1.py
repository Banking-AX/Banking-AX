from __future__ import annotations

import unittest

from app.core.staff_engine import CheckRegistry, CheckRequest, FakeCasePort, StaffCheckExecutor
from app.core.staff_engine.fakes import FakeCase
from app.core.staff_engine.rag_adapter import RagEvidenceAdapter
from app.customer_agent.fakes import FakeSpringToolGateway
from app.customer_agent.router import build_registry
from app.tasks.c01 import adapt_c01_response, register_c01_check


class C01G1Tests(unittest.TestCase):
    def test_customer_agent_starts_structured_c01_intake(self) -> None:
        response = build_registry(FakeSpringToolGateway()).get('DEBT_ADJUSTMENT').run({
            'requestId': 'c01-start-1', 'customerId': 1, 'workflowId': 10, 'loanId': 101,
            'expectedWorkflowVersion': 0, 'registeredPhone': '01012345678',
        })
        self.assertEqual(response['taskCode'], 'DEBT_ADJUSTMENT')
        self.assertEqual(response['data']['caseStatus'], 'DRAFT')

    def test_registered_langgraph_check_returns_staff_items_and_stale_is_rejected(self) -> None:
        registry = CheckRegistry(); register_c01_check(registry)
        executor = StaffCheckExecutor(FakeCasePort([FakeCase(300, 2, 'DEBT_ADJUSTMENT')]), registry, RagEvidenceAdapter(lambda *_: []))
        self.assertTrue(executor._graph.__class__.__module__.startswith('langgraph'))
        response = adapt_c01_response(executor.run(CheckRequest(
            request_id='c01-check', case_id=300, revision=2, task_code='DEBT_ADJUSTMENT',
            case_snapshot={'reason': '', 'currentSituation': '', 'ruleResults': [
                {'ruleCode': 'C1-R5', 'disposition': 'NEEDS_STAFF_REVIEW', 'message': 'other procedure'},
                {'ruleCode': 'C1-W3', 'disposition': 'NEEDS_STAFF_REVIEW', 'message': 'prescription'},
            ]},
        )))
        self.assertEqual(response.review_status, 'WAITING_CUSTOMER')
        self.assertEqual({item.category for item in response.review_items}, {'CUSTOMER_FACTS', 'C1-R5', 'C1-W3'})
        stale = executor.run(CheckRequest(request_id='c01-stale', case_id=300, revision=1, task_code='DEBT_ADJUSTMENT'))
        self.assertEqual(stale.error['code'], 'STALE_REVISION')


if __name__ == '__main__':
    unittest.main()
