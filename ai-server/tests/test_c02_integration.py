from __future__ import annotations

import os
import unittest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.customer_agent.fakes import FakeSpringToolGateway
from app.customer_agent.router import build_registry, create_router
from app.staff_agent.router import build_executor, create_router as create_staff_router


class C02IntegrationTests(unittest.TestCase):
    def test_registered_customer_task_preserves_structured_c02_payload(self) -> None:
        registry = build_registry(FakeSpringToolGateway())
        result = registry.get('INHERITANCE_DEPOSIT').run({
            'requestId': 'c02-router-1', 'workItemId': 1001, 'customerId': 1,
            'decedent': {'name': 'fixture', 'deathDate': '2020-01-01', 'depositTotal': 0},
            'heirs': [{'personRef': 'h1', 'name': 'heir', 'relationship': 'child', 'applicant': True, 'visitStatus': 'ATTENDING', 'delegationMethod': 'NONE'}],
            'documentStatuses': {}, 'additionalInput': {'ignoredByRuleEngine': 'keptAtRequestBoundary'},
        })
        self.assertEqual(result['taskCode'], 'INHERITANCE_DEPOSIT')
        self.assertEqual(result['intake']['heirs'][0]['personRef'], 'h1')
        self.assertFalse(result['submitted'])

    def test_staff_http_router_requires_token_and_uses_compiled_graph(self) -> None:
        old = os.environ.get('STAFF_AGENT_GATEWAY_TOKEN')
        os.environ['STAFF_AGENT_GATEWAY_TOKEN'] = 'staff-test-token'
        try:
            executor = build_executor()
            self.assertTrue(executor._graph.__class__.__module__.startswith('langgraph'))
            app = FastAPI(); app.include_router(create_staff_router(executor), prefix='/api/v1')
            client = TestClient(app)
            payload = {'requestId': 'c02-staff-http', 'caseId': 501, 'revision': 1, 'taskCode': 'INHERITANCE_DEPOSIT', 'caseSnapshot': {'decedent': {'deathDate': '2020-01-01'}, 'heirs': [{'visitStatus': 'UNCONFIRMED_ABSENT'}]}}
            self.assertEqual(client.post('/api/v1/staff-agent/check', json=payload).status_code, 403)
            response = client.post('/api/v1/staff-agent/check', headers={'X-Staff-Agent-Token': 'staff-test-token'}, json=payload)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()['status'], 'SUCCEEDED')
            self.assertEqual(response.json()['reviewStatus'], 'READY_FOR_STAFF')
            stale = client.post('/api/v1/staff-agent/check', headers={'X-Staff-Agent-Token': 'staff-test-token'}, json={**payload, 'requestId': 'c02-staff-stale', 'revision': 2})
            self.assertEqual(stale.json()['error']['code'], 'STALE_REVISION')
            unknown = client.post('/api/v1/staff-agent/check', headers={'X-Staff-Agent-Token': 'staff-test-token'}, json={**payload, 'requestId': 'unknown', 'caseId': 502, 'taskCode': 'NOT_REGISTERED'})
            self.assertEqual(unknown.json()['error']['code'], 'CHECK_NOT_REGISTERED')
        finally:
            if old is None: os.environ.pop('STAFF_AGENT_GATEWAY_TOKEN', None)
            else: os.environ['STAFF_AGENT_GATEWAY_TOKEN'] = old


if __name__ == '__main__':
    unittest.main()
