from __future__ import annotations

import json
import os
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.customer_agent.registry import TaskRegistry
from app.customer_agent.router import create_router
from app.customer_agent.tasks.transaction_search import TransactionSearchTask
from app.customer_agent.tool_gateway import SpringHttpToolGateway


class _SpringToolHandler(BaseHTTPRequestHandler):
    requests: list[tuple[str, dict[str, str], dict]] = []
    mode = "success"

    def do_POST(self) -> None:  # noqa: N802 - stdlib handler contract
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        type(self).requests.append((self.path, dict(self.headers), body))
        if type(self).mode == "timeout":
            time.sleep(0.2)
            return
        if type(self).mode == "error":
            self.send_response(503)
            self.end_headers()
            self.wfile.write(b'{"error":{"code":"TEMPORARY"}}')
            return
        payload = {
            "requestId": body["requestId"], "taskCode": "TRANSACTION_SEARCH", "workItemId": body["workItemId"],
            "caseId": None, "revision": None, "error": None,
            "data": {"accountId": body["accountId"], "accountNoMasked": "110-***-0001", "accountName": "Synthetic checking",
                     "timeZone": "Asia/Seoul", "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
                     "transactions": [{"transactionId": 10, "occurredAt": "2026-10-15T09:00:00", "direction": "IN", "amount": 1000, "counterparty": "Synthetic payroll", "memo": "salary"}]},
        }
        encoded = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, *_: object) -> None:
        pass


class A01SpringToolHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _SpringToolHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.thread.join()

    def setUp(self) -> None:
        _SpringToolHandler.requests = []
        _SpringToolHandler.mode = "success"

    def request(self) -> dict:
        return {"taskCode": "TRANSACTION_SEARCH", "requestId": "a01-http-1", "workItemId": 101,
                "customerId": 1, "accountId": 1001, "startDate": "2026-10-01", "endDate": "2026-10-31",
                "transactionType": "IN", "minimumAmount": "100", "sortOrder": "RECENT", "page": 0, "size": 20}

    def gateway(self, timeout_seconds: float = 0.1) -> SpringHttpToolGateway:
        return SpringHttpToolGateway(self.base_url, timeout_seconds)

    @patch.dict(os.environ, {"SPRING_TOOL_GATEWAY_TOKEN": "test-tool-token"})
    def test_fastapi_langgraph_and_http_gateway_forward_only_the_a01_contract(self) -> None:
        app = FastAPI()
        app.include_router(create_router(
            TaskRegistry([TransactionSearchTask(self.gateway())]),
            internal_token="agent-test-token"), prefix="/api/v1")

        response = TestClient(app).post(
            "/api/v1/customer-agent/execute",
            headers={"X-Customer-Agent-Token": "agent-test-token"}, json=self.request())

        self.assertEqual(200, response.status_code)
        self.assertEqual("TRANSACTION_SEARCH", response.json()["taskCode"])
        self.assertEqual(1, len(_SpringToolHandler.requests))
        path, headers, body = _SpringToolHandler.requests[0]
        self.assertEqual("/api/v1/tools/transaction-search", path)
        self.assertEqual("CUSTOMER", headers["X-Actor-Role"])
        self.assertEqual("1", headers["X-Actor-Id"])
        self.assertEqual("test-tool-token", headers["X-Tool-Gateway-Token"])
        self.assertEqual({"requestId", "workItemId", "customerId", "accountId", "startDate", "endDate", "transactionType", "minimumAmount", "sortOrder", "page", "size"}, set(body))

    @patch.dict(os.environ, {"SPRING_TOOL_GATEWAY_TOKEN": "test-tool-token"})
    def test_http_failure_and_timeout_stop_the_agent_without_a_transaction_response(self) -> None:
        _SpringToolHandler.mode = "error"
        with self.assertRaisesRegex(RuntimeError, "SPRING_TOOL_HTTP_503"):
            TransactionSearchTask(self.gateway()).run(self.request())
        _SpringToolHandler.mode = "timeout"
        # Timeout normalization belongs in the shared SpringHttpToolGateway.  Until
        # that owner maps socket timeouts, the A-01 graph must still stop rather
        # than construct a ledger response.
        with self.assertRaises(TimeoutError):
            TransactionSearchTask(self.gateway(0.01)).run(self.request())

    def test_invalid_tool_arguments_are_rejected_before_http_execution(self) -> None:
        invalid = self.request() | {"transactionType": "SIDEWAYS", "minimumAmount": "1000", "maximumAmount": "1"}
        with self.assertRaisesRegex(ValueError, "transactionType"):
            TransactionSearchTask(self.gateway()).run(invalid)
        self.assertEqual([], _SpringToolHandler.requests)
