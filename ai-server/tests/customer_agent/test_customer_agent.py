import unittest

from fastapi import FastAPI
from fastapi.testclient import TestClient
from app.customer_agent.fakes import FakeSpringToolGateway, FakeT2Ports
from app.customer_agent.registry import TaskRegistry
from app.customer_agent.router import create_router
from app.customer_agent.tasks.loan_inquiry import LoanInquiryTask
from app.customer_agent.tasks.transaction_search import TransactionSearchTask
from app.customer_agent.tasks.housing_savings_close import HousingSavingsCloseTask


class CustomerAgentContractTests(unittest.TestCase):
    def test_explicit_a02_registration_and_fake_roundtrip(self) -> None:
        registry = TaskRegistry([LoanInquiryTask(FakeSpringToolGateway())])
        result = registry.get("LOAN_INQUIRY").run({"requestId": "req-a02-test", "workItemId": 1001, "customerId": 1})
        self.assertEqual(101, result["data"]["loans"][0]["loanId"])

    def test_a01_registration_requires_structured_conditions_and_uses_langgraph(self) -> None:
        registry = TaskRegistry([TransactionSearchTask(FakeSpringToolGateway())])
        result = registry.get("TRANSACTION_SEARCH").run({"requestId": "req-a01-test", "workItemId": 1001, "customerId": 1,
            "accountId": 1001, "startDate": "2026-10-01", "endDate": "2026-10-31", "page": 0, "size": 20})
        self.assertEqual("TRANSACTION_SEARCH", result["taskCode"])
        self.assertEqual(1001, result["data"]["accountId"])
    def test_b02_prepare_does_not_execute_without_explicit_confirmation(self) -> None:
        task=HousingSavingsCloseTask(FakeSpringToolGateway())
        self.assertEqual("B2-R3",task.run({"requestId":"b","customerId":1,"accountId":3001,"waivePreferredRate":True})["data"]["rule"])
        with self.assertRaises(ValueError): task.run({"requestId":"e","customerId":1,"accountId":3001,"submissionConfirmed":True})


    def test_t2_fake_ports_keep_case_boundaries_explicit(self) -> None:
        ports = FakeT2Ports()
        self.assertEqual("SUCCEEDED", ports.register_check(2001, 0, "DEBT_ADJUSTMENT")["status"])
        self.assertEqual("READY_FOR_STAFF", ports.register_check(2001, 0, "DEBT_ADJUSTMENT")["reviewStatus"])
        self.assertEqual("SUCCEEDED", ports.register_check(2002, 0, "INHERITANCE_DEPOSIT")["status"])

    def test_router_exposes_a02_without_editing_shared_main(self) -> None:
        app = FastAPI()
        app.include_router(create_router(
            TaskRegistry([LoanInquiryTask(FakeSpringToolGateway())]),
            internal_token="agent-test-token"), prefix="/api/v1")
        response = TestClient(app).post(
            "/api/v1/customer-agent/execute",
            headers={"X-Customer-Agent-Token": "agent-test-token"}, json={
            "taskCode": "LOAN_INQUIRY", "requestId": "req-router", "workItemId": 1001, "customerId": 1,
        })
        self.assertEqual(200, response.status_code)
        self.assertEqual("LOAN_INQUIRY", response.json()["taskCode"])

    def test_execute_requires_the_dedicated_internal_token(self) -> None:
        app = FastAPI()
        app.include_router(create_router(
            TaskRegistry([LoanInquiryTask(FakeSpringToolGateway())]),
            internal_token="agent-test-token"), prefix="/api/v1")
        payload = {"taskCode": "LOAN_INQUIRY", "requestId": "req-auth",
                   "workItemId": 1001, "customerId": 1}
        client = TestClient(app)

        missing = client.post("/api/v1/customer-agent/execute", json=payload)
        invalid = client.post("/api/v1/customer-agent/execute",
                              headers={"X-Customer-Agent-Token": "wrong"}, json=payload)

        self.assertEqual(401, missing.status_code)
        self.assertEqual(401, invalid.status_code)
