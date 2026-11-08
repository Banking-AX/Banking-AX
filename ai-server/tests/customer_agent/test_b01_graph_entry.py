from __future__ import annotations

import unittest
import tempfile
from pathlib import Path
from typing import get_args, get_type_hints
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.customer_agent.intent_classifier import (
    ClassifiedTask,
    IntentClassification,
    RequestKind,
    TaskCode,
)
from app.customer_agent.b01_checkpoint import B01CheckpointStore
from app.customer_agent.registry import TaskRegistry
from app.customer_agent.router import create_router
from app.customer_agent.tasks.repayment_order_change import B01GraphState
from app.customer_agent.tool_gateway import SpringHttpToolGateway, SpringToolHttpError


def classified(task_code: TaskCode, request_kind: RequestKind) -> IntentClassification:
    task = ClassifiedTask(
        order=1,
        taskCode=task_code,
        requestKind=request_kind,
        executionAvailable=True,
    )
    return IntentClassification(
        tasks=[task],
        executionTargets=[task_code] if request_kind is RequestKind.ACTION else [],
        requiresClarification=False,
        clarificationQuestion=None,
        unsupported=False,
        unsupportedReason=None,
    )


class StaticClassifier:
    def classify(self, message: str,
                 recent_messages: list[str] | None = None) -> IntentClassification:
        if message == "B-01 안내":
            return classified(TaskCode.REPAYMENT_ORDER_CHANGE, RequestKind.INFORMATION)
        if message == "대출 조회":
            return classified(TaskCode.LOAN_INQUIRY, RequestKind.ACTION)
        return classified(TaskCode.REPAYMENT_ORDER_CHANGE, RequestKind.ACTION)


class RecordingGateway:
    def __init__(self, outcome: str = "CREATED", status: str = "ACTIVE") -> None:
        self.outcome = outcome
        self.status = status
        self.b01_requests: list[dict[str, object]] = []
        self.selection_requests: list[dict[str, object]] = []
        self.failure: Exception | None = None

    def b01_prepare(self, request: dict[str, object]) -> dict[str, object]:
        self.b01_requests.append(request)
        if self.failure is not None:
            raise self.failure
        return {
            "requestId": request["requestId"],
            "taskCode": "REPAYMENT_ORDER_CHANGE",
            "workItemId": 1001,
            "caseId": None,
            "revision": None,
            "data": {
                "outcome": self.outcome,
                "workItem": {
                    "workflowId": 501,
                    "workItemId": 1001,
                    "taskCode": "REPAYMENT_ORDER_CHANGE",
                    "status": self.status,
                },
            },
            "error": None,
        }

    def workflow_start(self, request: dict[str, object]) -> dict[str, object]:
        raise AssertionError("B-01 entry must use the dedicated prepare API")

    def b01_loans(self, request: dict[str, object]) -> dict[str, object]:
        return self._tool_envelope(request, {"loans": [{
            "loanId": 501, "loanName": "직장인대출",
            "loanNoMasked": "210-***-000001", "repaymentOrder": "INTEREST_FIRST",
        }]})

    def b01_selection(self, request: dict[str, object]) -> dict[str, object]:
        self.selection_requests.append(request)
        return self._tool_envelope(request, {
            "workItemId": request["workItemId"],
            "selectedLoanId": request["selectedLoanId"],
            "requestedRepaymentOrder": request["requestedRepaymentOrder"],
            "changed": True, "replayed": False, "workItemVersion": 1,
        })

    def b01_eligibility(self, request: dict[str, object]) -> dict[str, object]:
        selection = self.selection_requests[-1]
        return self._tool_envelope(request, {
            "workItemId": request["workItemId"],
            "selectedLoanId": selection["selectedLoanId"],
            "requestedRepaymentOrder": selection["requestedRepaymentOrder"],
            "eligibilityStatus": "ELIGIBLE", "reasonCode": "ELIGIBLE",
            "reasonMessage": "대출 규칙 사전검증을 통과했습니다.",
        })

    @staticmethod
    def _tool_envelope(request: dict[str, object], data: dict[str, object]) -> dict[str, object]:
        return {"requestId": request["requestId"], "taskCode": "REPAYMENT_ORDER_CHANGE",
                "workItemId": request["workItemId"], "caseId": None, "revision": None,
                "data": data, "error": None}


class B01GraphEntryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.store = B01CheckpointStore(Path(self.temporary.name) / "router.sqlite3").open()

    def tearDown(self) -> None:
        self.store.close()
        self.temporary.cleanup()

    def client(self, gateway: RecordingGateway, enabled: bool = True) -> TestClient:
        app = FastAPI()
        app.include_router(create_router(
            TaskRegistry(),
            StaticClassifier(),
            gateway,
            natural_start_enabled=True,
            b01_graph_entry_enabled=enabled,
            b01_checkpoint_store=self.store,
            internal_token="agent-test-token",
        ), prefix="/api/v1")
        return TestClient(app)

    @staticmethod
    def post(client: TestClient, message: str = "원금 우선으로 변경", request_id: str = "b01-entry-1"):
        return client.post(
            "/api/v1/customer-agent/messages/start",
            headers={"X-Customer-Agent-Token": "agent-test-token"},
            json={"requestId": request_id, "customerId": 7, "chatRoomId": 77,
                  "message": message},
        )

    def test_action_prepares_work_item_and_initializes_graph_state(self) -> None:
        gateway = RecordingGateway()
        response = self.post(self.client(gateway))

        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("TASK_ACTION_AVAILABLE", body["outcome"])
        self.assertEqual(1001, body["workflow"]["workItemId"])
        self.assertEqual("b01:1001", body["b01Entry"]["threadId"])
        self.assertEqual("CREATED", body["b01Entry"]["prepareOutcome"])
        self.assertEqual(7, body["b01Entry"]["state"]["customer_id"])
        self.assertEqual(501, body["b01Entry"]["state"]["selected_loan_id"])
        self.assertEqual("PRINCIPAL_FIRST", body["b01Entry"]["state"]["requested_repayment_order"])
        self.assertEqual("ELIGIBLE", body["b01Entry"]["state"]["eligibility_status"])
        self.assertEqual("신청 화면으로 이동", body["taskAction"]["label"])
        self.assertEqual([{
            "requestId": "b01-entry-1",
            "customerId": 7,
            "chatRoomId": 77,
            "mode": "DISCOVER",
        }], gateway.b01_requests)

    def test_awaiting_input_contract_uses_named_wait_states_and_none(self) -> None:
        awaiting_type = get_type_hints(B01GraphState)["awaiting_input"]
        literal_type, none_type = get_args(awaiting_type)

        self.assertEqual(type(None), none_type)
        self.assertEqual({
            "LOAN_SELECTION",
            "LOAN_CONFIRMATION",
            "REPAYMENT_ORDER_SELECTION",
        }, set(get_args(literal_type)))

    def test_same_chat_reuse_initializes_state_with_spring_work_item(self) -> None:
        gateway = RecordingGateway("SAME_CHAT_REUSED")
        response = self.post(self.client(gateway))

        self.assertEqual(200, response.status_code)
        self.assertEqual("SAME_CHAT_REUSED", response.json()["b01Entry"]["prepareOutcome"])
        self.assertEqual(1001, response.json()["b01Entry"]["state"]["work_item_id"])

    def test_information_request_does_not_prepare_work_item(self) -> None:
        gateway = RecordingGateway()
        response = self.post(self.client(gateway), "B-01 안내")

        self.assertEqual(200, response.status_code)
        self.assertEqual("INFORMATION_ONLY", response.json()["outcome"])
        self.assertEqual([], gateway.b01_requests)

    def test_reconnect_confirmation_does_not_initialize_graph(self) -> None:
        gateway = RecordingGateway("RECONNECT_CONFIRMATION_REQUIRED", "ON_HOLD")
        response = self.post(self.client(gateway))

        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("B01_RECONNECT_CONFIRMATION_REQUIRED", body["outcome"])
        self.assertIsNone(body["workflow"])
        self.assertIsNone(body["taskAction"])
        self.assertIsNone(body["b01Entry"])
        self.assertIn("이어갈지", body["answer"])

    def test_active_work_item_conflict_does_not_initialize_graph(self) -> None:
        gateway = RecordingGateway("ACTIVE_WORK_ITEM_CONFLICT")
        response = self.post(self.client(gateway))

        self.assertEqual(200, response.status_code)
        self.assertEqual("B01_ACTIVE_WORK_ITEM_CONFLICT", response.json()["outcome"])
        self.assertIsNone(response.json()["b01Entry"])

    def test_prepare_failure_is_not_reported_as_financial_ineligibility(self) -> None:
        gateway = RecordingGateway()
        gateway.failure = RuntimeError("sensitive upstream detail")
        response = self.post(self.client(gateway))

        self.assertEqual(503, response.status_code)
        self.assertEqual("B01_PREPARE_UNAVAILABLE", response.json()["detail"])
        self.assertNotIn("sensitive", response.text)

    def test_duplicate_request_conflict_is_preserved(self) -> None:
        gateway = RecordingGateway()
        gateway.failure = SpringToolHttpError(409, "DUPLICATE_REQUEST_CONFLICT")
        response = self.post(self.client(gateway))

        self.assertEqual(409, response.status_code)
        self.assertEqual("DUPLICATE_REQUEST_CONFLICT", response.json()["detail"])

    def test_feature_flag_disabled_preserves_existing_screen_action(self) -> None:
        gateway = RecordingGateway()
        response = self.post(self.client(gateway, enabled=False))

        self.assertEqual(200, response.status_code)
        self.assertEqual("TASK_ACTION_AVAILABLE", response.json()["outcome"])
        self.assertIsNone(response.json()["workflow"])
        self.assertIsNone(response.json()["b01Entry"])
        self.assertEqual([], gateway.b01_requests)

    def test_retry_forwards_the_same_customer_and_request_identifiers(self) -> None:
        gateway = RecordingGateway("REQUEST_REPLAYED")
        client = self.client(gateway)

        first = self.post(client, request_id="stable-b01-request")
        second = self.post(client, request_id="stable-b01-request")

        self.assertEqual(200, first.status_code)
        self.assertEqual(200, second.status_code)
        self.assertEqual(["stable-b01-request"],
                         [item["requestId"] for item in gateway.b01_requests])
        self.assertEqual([7], [item["customerId"] for item in gateway.b01_requests])
        self.assertEqual("b01:1001", second.json()["b01Entry"]["threadId"])


class B01PrepareHttpGatewayTests(unittest.TestCase):
    @patch.dict("os.environ", {"SPRING_TOOL_GATEWAY_TOKEN": "test-gateway-token"})
    def test_uses_existing_internal_auth_headers_and_exact_prepare_contract(self) -> None:
        gateway = SpringHttpToolGateway("http://spring.invalid")
        expected = {"data": {"outcome": "CREATED"}}

        with patch.object(gateway, "_post", return_value=expected) as post:
            actual = gateway.b01_prepare({
                "requestId": "b01-http-1",
                "customerId": 7,
                "chatRoomId": 77,
                "mode": "DISCOVER",
            })

        self.assertEqual(expected, actual)
        post.assert_called_once_with(
            "/api/v1/tools/workflows/b01/prepare",
            {"requestId": "b01-http-1", "customerId": 7,
             "chatRoomId": 77, "mode": "DISCOVER"},
            {"Content-Type": "application/json", "X-Actor-Id": "7",
             "X-Actor-Role": "CUSTOMER", "X-Tool-Gateway-Token": "test-gateway-token"},
        )


if __name__ == "__main__":
    unittest.main()
