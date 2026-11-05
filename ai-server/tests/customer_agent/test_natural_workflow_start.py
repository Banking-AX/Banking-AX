from __future__ import annotations

import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.customer_agent.intent_classifier import (
    ClassifiedTask,
    IntentClassification,
    RequestKind,
    TaskCode,
)
from app.customer_agent.registry import TaskRegistry
from app.customer_agent.router import create_router
from app.customer_agent.tasks.loan_inquiry import LoanInquiryTask
from app.customer_agent.tool_gateway import SpringHttpToolGateway, SpringToolHttpError


def classified(*tasks: tuple[TaskCode, RequestKind, bool], clarification: bool = False,
               unsupported: bool = False) -> IntentClassification:
    items = [ClassifiedTask(order=index, taskCode=code, requestKind=kind, executionAvailable=available)
             for index, (code, kind, available) in enumerate(tasks, 1)]
    return IntentClassification(
        tasks=items,
        executionTargets=[item.taskCode for item in items
                          if item.requestKind is RequestKind.ACTION and item.executionAvailable],
        requiresClarification=clarification,
        clarificationQuestion="어떤 대출 업무를 원하시나요?" if clarification else None,
        unsupported=unsupported,
        unsupportedReason="지원하지 않는 요청입니다." if unsupported else None,
    )


class StaticClassifier:
    def __init__(self, results: dict[str, IntentClassification]) -> None:
        self.results = results

    def classify(self, message: str, recent_messages: list[str] | None = None) -> IntentClassification:
        return self.results[message]


class RecordingWorkflowGateway:
    def __init__(self) -> None:
        self.requests: list[dict[str, object]] = []
        self.loan_requests: list[dict[str, object]] = []
        self.workflow_fail = False
        self.workflow_conflict = False
        self.loan_fail = False

    def workflow_start(self, request: dict[str, object]) -> dict[str, object]:
        self.requests.append(request)
        if self.workflow_fail:
            raise RuntimeError("SPRING_TOOL_HTTP_503: sensitive upstream detail")
        if self.workflow_conflict:
            raise SpringToolHttpError(409, "DUPLICATE_REQUEST_CONFLICT")
        status = "DONE" if self.loan_requests else "ACTIVE"
        return {
            "requestId": request["requestId"], "taskCode": request["taskCode"], "workItemId": 1001,
            "caseId": None, "revision": None, "error": None,
            "data": {"workflowId": 501, "workItemId": 1001,
                     "taskCode": request["taskCode"], "status": status},
        }

    def loan_inquiry(self, request: dict[str, object]) -> dict[str, object]:
        self.loan_requests.append(request)
        if self.loan_fail:
            raise RuntimeError("SPRING_TOOL_HTTP_500: sensitive loan detail")
        loans = [
            {"loanId": 1, "loanNoMasked": "210-***-000001", "loanName": "정상 대출",
             "balance": 1000000, "interestRate": 3.5, "overdueDays": 0,
             "accelerationDeclared": False, "repaymentOrder": "INTEREST_FIRST"},
            {"loanId": 2, "loanNoMasked": "210-***-000002", "loanName": "연체 대출",
             "balance": 2000000, "interestRate": 4.5, "overdueDays": 30,
             "accelerationDeclared": True, "repaymentOrder": "PRINCIPAL_FIRST"},
            {"loanId": 3, "loanNoMasked": "210-***-000003", "loanName": "제외조건 대출",
             "balance": 3000000, "interestRate": 5.5, "overdueDays": 0,
             "accelerationDeclared": False, "repaymentOrder": "PRINCIPAL_FIRST"},
        ]
        return {"requestId": request["requestId"], "taskCode": "LOAN_INQUIRY",
                "workItemId": request["workItemId"], "caseId": None, "revision": None,
                "data": {"loans": loans, "workItemStatus": "DONE",
                         "workflowStatus": "COMPLETED"}, "error": None}


class NaturalWorkflowStartTests(unittest.TestCase):
    def setUp(self) -> None:
        self.results = {
            "내 대출 잔액 알려줘": classified((TaskCode.LOAN_INQUIRY, RequestKind.ACTION, True)),
            "대출 조회 방법 알려줘": classified((TaskCode.LOAN_INQUIRY, RequestKind.INFORMATION, True)),
            "대출 업무 처리해줘": classified(clarification=True),
            "적금 가입해줘": classified(unsupported=True),
            "대출과 거래내역 조회해줘": classified(
                (TaskCode.LOAN_INQUIRY, RequestKind.ACTION, True),
                (TaskCode.TRANSACTION_SEARCH, RequestKind.ACTION, True)),
            "채무조정 신청해줘": classified((TaskCode.DEBT_ADJUSTMENT, RequestKind.ACTION, True)),
            "충당순서 바꿔줘": classified((TaskCode.REPAYMENT_ORDER_CHANGE, RequestKind.ACTION, True)),
        }
        self.gateway = RecordingWorkflowGateway()
        app = FastAPI()
        app.include_router(create_router(
            TaskRegistry([LoanInquiryTask(self.gateway)]), StaticClassifier(self.results),
            self.gateway, natural_start_enabled=True, internal_token="agent-test-token"),
            prefix="/api/v1")
        self.client = TestClient(app)

    def post(self, message: str, request_id: str = "natural-a02-1"):
        return self.client.post(
            "/api/v1/customer-agent/messages/start",
            headers={"X-Customer-Agent-Token": "agent-test-token"},
            json={"requestId": request_id, "customerId": 1, "chatRoomId": 77,
                  "message": message})

    def test_single_a02_action_starts_workflow_and_returns_three_loans(self) -> None:
        response = self.post("내 대출 잔액 알려줘")

        self.assertEqual(200, response.status_code)
        self.assertEqual("STARTED", response.json()["outcome"])
        self.assertEqual({"workflowId": 501, "workItemId": 1001,
                          "taskCode": "LOAN_INQUIRY", "status": "COMPLETED"}, response.json()["workflow"])
        self.assertEqual(3, len(response.json()["loanInquiry"]["loans"]))
        self.assertEqual("DONE", response.json()["executionStatus"]["workItemStatus"])
        self.assertEqual("COMPLETED", response.json()["executionStatus"]["workflowStatus"])
        self.assertIn("대출계좌 3건", response.json()["answer"])
        self.assertIn("210-***-000001", response.json()["answer"])
        self.assertIn("1,000,000원", response.json()["answer"])
        self.assertEqual("GENERATED", response.json()["answerStatus"])
        self.assertTrue(all("***" in loan["loanNoMasked"]
                            for loan in response.json()["loanInquiry"]["loans"]))
        self.assertEqual([{"requestId": "natural-a02-1", "customerId": 1, "chatRoomId": 77,
                           "taskCode": "LOAN_INQUIRY"}], self.gateway.requests)
        self.assertEqual(1001, self.gateway.loan_requests[0]["workItemId"])

    def test_non_action_ambiguous_unsupported_composite_and_other_tasks_never_call_spring(self) -> None:
        expected = {
            "대출 조회 방법 알려줘": "INFORMATION_ONLY",
            "대출 업무 처리해줘": "CLARIFICATION_REQUIRED",
            "적금 가입해줘": "UNSUPPORTED",
            "대출과 거래내역 조회해줘": "COMPOSITE_NOT_SUPPORTED",
            "채무조정 신청해줘": "TASK_NOT_AUTOSTART_ENABLED",
            "충당순서 바꿔줘": "TASK_ACTION_AVAILABLE",
        }
        for message, outcome in expected.items():
            with self.subTest(message=message):
                response = self.post(message, f"request-{len(self.gateway.requests)}")
                self.assertEqual(200, response.status_code)
                self.assertEqual(outcome, response.json()["outcome"])
                self.assertIsNone(response.json()["workflow"])
                self.assertIsNone(response.json()["loanInquiry"])
                self.assertIsNotNone(response.json()["answer"])
                if message == "충당순서 바꿔줘":
                    self.assertEqual({
                        "taskCode": "REPAYMENT_ORDER_CHANGE",
                        "label": "신청 화면으로 이동",
                        "actionRequestId": response.json()["requestId"],
                    }, response.json()["taskAction"])
        self.assertEqual([], self.gateway.requests)
        self.assertEqual([], self.gateway.loan_requests)

    def test_retry_forwards_the_original_request_id_and_returns_the_same_result(self) -> None:
        first = self.post("내 대출 잔액 알려줘", "stable-request")
        second = self.post("내 대출 잔액 알려줘", "stable-request")

        self.assertEqual(first.json()["workflow"]["workflowId"], second.json()["workflow"]["workflowId"])
        self.assertEqual(first.json()["workflow"]["workItemId"], second.json()["workflow"]["workItemId"])
        self.assertEqual("COMPLETED", first.json()["workflow"]["status"])
        self.assertEqual("COMPLETED", second.json()["workflow"]["status"])
        self.assertEqual(first.json()["loanInquiry"], second.json()["loanInquiry"])
        self.assertEqual(["stable-request", "stable-request"],
                         [request["requestId"] for request in self.gateway.requests])
        self.assertEqual([1001, 1001], [request["workItemId"] for request in self.gateway.loan_requests])

    def test_spring_failure_is_sanitized_and_never_reported_as_started(self) -> None:
        self.gateway.workflow_fail = True

        response = self.post("내 대출 잔액 알려줘")

        self.assertEqual(503, response.status_code)
        self.assertEqual("WORKFLOW_START_UNAVAILABLE", response.json()["detail"])
        self.assertNotIn("sensitive", response.text)
        self.assertEqual([], self.gateway.loan_requests)

    def test_trusted_workflow_duplicate_conflict_is_preserved_without_internal_detail(self) -> None:
        self.gateway.workflow_conflict = True

        response = self.post("내 대출 잔액 알려줘")

        self.assertEqual(409, response.status_code)
        self.assertEqual("DUPLICATE_REQUEST_CONFLICT", response.json()["detail"])
        self.assertEqual([], self.gateway.loan_requests)

    def test_loan_inquiry_failure_is_sanitized_after_workflow_start(self) -> None:
        self.gateway.loan_fail = True

        response = self.post("내 대출 잔액 알려줘")

        self.assertEqual(503, response.status_code)
        self.assertEqual("LOAN_INQUIRY_UNAVAILABLE", response.json()["detail"])
        self.assertNotIn("sensitive", response.text)
        self.assertEqual(1, len(self.gateway.requests))
        self.assertEqual(1, len(self.gateway.loan_requests))

    def test_natural_start_route_is_absent_when_disabled(self) -> None:
        app = FastAPI()
        app.include_router(create_router(
            TaskRegistry([LoanInquiryTask(self.gateway)]), StaticClassifier(self.results),
            self.gateway, natural_start_enabled=False, internal_token="agent-test-token"),
            prefix="/api/v1")

        response = TestClient(app).post("/api/v1/customer-agent/messages/start", json={
            "requestId": "disabled", "customerId": 1, "chatRoomId": 77,
            "message": "내 대출 잔액 알려줘",
        })

        self.assertEqual(404, response.status_code)

    def test_natural_start_rejects_missing_or_invalid_internal_token(self) -> None:
        payload = {"requestId": "unauthorized", "customerId": 1, "chatRoomId": 77,
                   "message": "내 대출 잔액 알려줘"}
        missing = self.client.post("/api/v1/customer-agent/messages/start", json=payload)
        invalid = self.client.post(
            "/api/v1/customer-agent/messages/start",
            headers={"X-Customer-Agent-Token": "wrong"}, json=payload)

        self.assertEqual(401, missing.status_code)
        self.assertEqual(401, invalid.status_code)
        self.assertEqual([], self.gateway.requests)


class _WorkflowStartHandler(BaseHTTPRequestHandler):
    received: tuple[str, dict[str, str], dict[str, object]] | None = None
    conflict = False

    def do_POST(self) -> None:  # noqa: N802 - stdlib handler contract
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        type(self).received = (self.path, dict(self.headers), body)
        if type(self).conflict:
            payload = {"requestId": body["requestId"], "data": None,
                       "error": {"code": "DUPLICATE_REQUEST_CONFLICT",
                                 "message": "sensitive upstream detail"}}
            encoded = json.dumps(payload).encode()
            self.send_response(409)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
            return
        payload = {
            "requestId": body["requestId"], "taskCode": body["taskCode"], "workItemId": 1001,
            "caseId": None, "revision": None, "error": None,
            "data": {"workflowId": 501, "workItemId": 1001,
                     "taskCode": body["taskCode"], "status": "ACTIVE"},
        }
        encoded = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, *_: object) -> None:
        pass


class WorkflowStartHttpGatewayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _WorkflowStartHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self) -> None:
        _WorkflowStartHandler.conflict = False

    @patch.dict(os.environ, {"SPRING_TOOL_GATEWAY_TOKEN": "workflow-test-token"})
    def test_http_gateway_reuses_internal_token_and_exact_spring_contract(self) -> None:
        gateway = SpringHttpToolGateway(f"http://127.0.0.1:{self.server.server_port}")

        response = gateway.workflow_start({
            "requestId": "http-start-1", "customerId": 1, "chatRoomId": 77,
            "taskCode": "LOAN_INQUIRY",
        })

        self.assertEqual("ACTIVE", response["data"]["status"])
        path, headers, body = _WorkflowStartHandler.received or (None, {}, {})
        self.assertEqual("/api/v1/tools/workflows/start", path)
        self.assertEqual("1", headers["X-Actor-Id"])
        self.assertEqual("CUSTOMER", headers["X-Actor-Role"])
        self.assertEqual("workflow-test-token", headers["X-Tool-Gateway-Token"])
        self.assertEqual({"requestId": "http-start-1", "customerId": 1, "chatRoomId": 77,
                          "taskCode": "LOAN_INQUIRY"}, body)

    @patch.dict(os.environ, {"SPRING_TOOL_GATEWAY_TOKEN": "workflow-test-token"})
    def test_http_gateway_extracts_only_the_structured_spring_error_code(self) -> None:
        _WorkflowStartHandler.conflict = True
        gateway = SpringHttpToolGateway(f"http://127.0.0.1:{self.server.server_port}")

        with self.assertRaises(SpringToolHttpError) as raised:
            gateway.workflow_start({
                "requestId": "http-conflict", "customerId": 1, "chatRoomId": 77,
                "taskCode": "LOAN_INQUIRY",
            })

        self.assertEqual(409, raised.exception.status_code)
        self.assertEqual("DUPLICATE_REQUEST_CONFLICT", raised.exception.error_code)
        self.assertNotIn("sensitive", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
