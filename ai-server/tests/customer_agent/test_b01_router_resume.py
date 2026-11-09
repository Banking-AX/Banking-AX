from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.customer_agent.b01_checkpoint import B01CheckpointStore
from app.customer_agent.intent_classifier import (
    ClassifiedTask,
    IntentClassification,
    RequestKind,
    TaskCode,
)
from app.customer_agent.registry import TaskRegistry
from app.customer_agent.router import create_router


def classification(task_code: TaskCode, kind: RequestKind = RequestKind.ACTION) -> IntentClassification:
    task = ClassifiedTask(order=1, taskCode=task_code, requestKind=kind, executionAvailable=True)
    return IntentClassification(
        tasks=[task], executionTargets=[task_code] if kind is RequestKind.ACTION else [],
        requiresClarification=False, clarificationQuestion=None,
        unsupported=False, unsupportedReason=None,
    )


class RecordingClassifier:
    def __init__(self) -> None:
        self.messages: list[str] = []

    def classify(self, message: str,
                 recent_messages: list[str] | None = None) -> IntentClassification:
        self.messages.append(message)
        compact = message.replace(" ", "")
        if "거래내역" in compact:
            return classification(TaskCode.TRANSACTION_SEARCH)
        if "안내" in compact or "뭐야" in compact:
            return classification(TaskCode.REPAYMENT_ORDER_CHANGE, RequestKind.INFORMATION)
        return classification(TaskCode.REPAYMENT_ORDER_CHANGE)


class RouterGateway:
    def __init__(self, loans: list[dict[str, object]]) -> None:
        self.loans = loans
        self.prepare_requests: list[dict[str, object]] = []
        self.loan_requests: list[dict[str, object]] = []
        self.selection_requests: list[dict[str, object]] = []
        self.eligibility_requests: list[dict[str, object]] = []
        self.workflow_requests: list[dict[str, object]] = []
        self.eligible = True
        self.loan_failure = False
        self.prepare_outcome = "CREATED"

    def b01_prepare(self, request: dict[str, Any]) -> dict[str, Any]:
        self.prepare_requests.append(request)
        work_item_id = 1000 + int(request["customerId"])
        return {"requestId": request["requestId"], "taskCode": "REPAYMENT_ORDER_CHANGE",
                "workItemId": work_item_id, "caseId": None, "revision": None,
                "data": {"outcome": self.prepare_outcome, "workItem": {
                    "workflowId": 5000 + int(request["customerId"]),
                    "workItemId": work_item_id, "taskCode": "REPAYMENT_ORDER_CHANGE",
                    "status": "ACTIVE"}}, "error": None}

    def b01_loans(self, request: dict[str, Any]) -> dict[str, Any]:
        self.loan_requests.append(request)
        if self.loan_failure:
            raise RuntimeError("SPRING_TOOL_UNAVAILABLE")
        return self._envelope(request, {"loans": self.loans})

    def b01_selection(self, request: dict[str, Any]) -> dict[str, Any]:
        self.selection_requests.append(request)
        return self._envelope(request, {
            "workItemId": request["workItemId"], "selectedLoanId": request["selectedLoanId"],
            "requestedRepaymentOrder": request["requestedRepaymentOrder"],
            "changed": True, "replayed": False, "workItemVersion": 1})

    def b01_eligibility(self, request: dict[str, Any]) -> dict[str, Any]:
        self.eligibility_requests.append(request)
        selection = self.selection_requests[-1]
        return self._envelope(request, {
            "workItemId": request["workItemId"], "selectedLoanId": selection["selectedLoanId"],
            "requestedRepaymentOrder": selection["requestedRepaymentOrder"],
            "eligibilityStatus": "ELIGIBLE" if self.eligible else "INELIGIBLE",
            "reasonCode": "ELIGIBLE" if self.eligible else "NOT_ACCELERATED",
            "reasonMessage": ("대출 규칙 사전검증을 통과했습니다."
                              if self.eligible else "기한의 이익이 상실된 대출만 신청할 수 있습니다.")})

    def workflow_start(self, request: dict[str, Any]) -> dict[str, Any]:
        self.workflow_requests.append(request)
        return {"requestId": request["requestId"], "taskCode": request["taskCode"],
                "workItemId": 9001, "caseId": None, "revision": None,
                "data": {"workflowId": 9000, "workItemId": 9001,
                         "taskCode": request["taskCode"], "status": "ACTIVE"}, "error": None}

    @staticmethod
    def _envelope(request: dict[str, Any], data: dict[str, Any]) -> dict[str, Any]:
        return {"requestId": request["requestId"], "taskCode": "REPAYMENT_ORDER_CHANGE",
                "workItemId": request["workItemId"], "caseId": None, "revision": None,
                "data": data, "error": None}


def candidate(loan_id: int, name: str, masked: str) -> dict[str, object]:
    return {"loanId": loan_id, "loanName": name, "loanNoMasked": masked,
            "repaymentOrder": "INTEREST_FIRST"}


class B01RouterResumeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "router.sqlite3"
        self.store = B01CheckpointStore(self.path).open()
        self.classifier = RecordingClassifier()
        self.gateway = RouterGateway([
            candidate(501, "직장인대출", "210-***-000001"),
            candidate(502, "주택담보대출", "210-***-000002"),
        ])
        self.client = self._client(self.store)

    def tearDown(self) -> None:
        self.store.close()
        self.temporary.cleanup()

    def _client(self, store: B01CheckpointStore) -> TestClient:
        app = FastAPI()
        app.include_router(create_router(
            TaskRegistry(), self.classifier, self.gateway,
            natural_start_enabled=True, b01_graph_entry_enabled=True,
            b01_checkpoint_store=store, internal_token="agent-test-token",
            information_guide=lambda *_: {
                "officialName": "채무변제 충당순서 변경",
                "bullets": [{"text": "원금 우선은 원금에 먼저 충당하는 순서를 뜻해요."}],
            }), prefix="/api/v1")
        return TestClient(app)

    def post(self, request_id: str, message: str, customer_id: int = 7,
             chat_room_id: int = 77, client: TestClient | None = None):
        return (client or self.client).post(
            "/api/v1/customer-agent/messages/start",
            headers={"X-Customer-Agent-Token": "agent-test-token"},
            json={"requestId": request_id, "customerId": customer_id,
                  "chatRoomId": chat_room_id, "message": message})

    def test_initial_action_interrupts_and_second_message_resumes_without_classification(self) -> None:
        first = self.post("initial-1", "원금 우선으로 바꿔줘")

        self.assertEqual(200, first.status_code)
        self.assertTrue(first.json()["b01Entry"]["interrupted"])
        self.assertEqual("LOAN_SELECTION", first.json()["b01Entry"]["awaitingInput"])
        self.assertEqual(
            first.json()["b01Entry"]["state"]["pending_question"],
            first.json()["answer"],
        )
        self.assertTrue(first.json()["answer"].strip())
        self.assertIsNone(first.json()["taskAction"])
        self.assertEqual("SELECT_B01_LOAN",
                         first.json()["b01LoanSelectionAction"]["type"])
        self.assertEqual([1, 2], [item["number"] for item in
                                  first.json()["b01LoanSelectionAction"]["candidates"]])

        resumed = self.post("followup-1", "2번")

        self.assertEqual(200, resumed.status_code)
        self.assertEqual("TASK_ACTION_AVAILABLE", resumed.json()["outcome"])
        self.assertEqual(502, resumed.json()["b01Entry"]["state"]["selected_loan_id"])
        self.assertEqual("initial-1", resumed.json()["taskAction"]["actionRequestId"])
        self.assertEqual(["원금 우선으로 바꿔줘"], self.classifier.messages)
        self.assertEqual(1, len(self.gateway.prepare_requests))
        self.assertEqual(1, len(self.gateway.loan_requests))

    def test_initial_action_lists_numbered_loans_then_fourth_loan_reaches_eligibility(self) -> None:
        self.gateway.loans = [
            candidate(index, f"테스트 대출 {index}", f"210-***-00000{index}")
            for index in range(1, 9)
        ]
        self.gateway.loans[3] = candidate(
            4, "B01-T01 가상 가계대출", "MOCK-***-T01")

        initial = self.post("full-flow-initial", "채무 순서 변경")

        self.assertEqual("LOAN_SELECTION", initial.json()["b01Entry"]["awaitingInput"])
        self.assertIn("말씀하신 업무는 채무변제 충당순서 변경입니다.", initial.json()["answer"])
        self.assertIn("4. B01-T01 가상 가계대출(MOCK-***-T01)", initial.json()["answer"])
        self.assertNotIn("기한의 이익", initial.json()["answer"])
        self.assertEqual([], self.gateway.selection_requests)
        self.assertEqual([], self.gateway.eligibility_requests)

        selected = self.post("full-flow-loan", "4번")

        self.assertEqual(4, selected.json()["b01Entry"]["state"]["selected_loan_id"])
        self.assertEqual("REPAYMENT_ORDER_SELECTION",
                         selected.json()["b01Entry"]["awaitingInput"])
        self.assertIn("B01-T01 가상 가계대출을 선택하셨습니다.", selected.json()["answer"])
        self.assertIn("현재 충당순서는 이자 우선입니다", selected.json()["answer"])
        self.assertIn("원금 우선으로 변경하시겠어요?", selected.json()["answer"])
        self.assertNotIn("이자 우선으로 변경", selected.json()["answer"])
        self.assertEqual([], self.gateway.eligibility_requests)

        eligible = self.post("full-flow-order", "응")

        self.assertEqual("ELIGIBLE", eligible.json()["b01Entry"]["state"]["eligibility_status"])
        self.assertEqual(4, self.gateway.selection_requests[-1]["selectedLoanId"])
        self.assertEqual("PRINCIPAL_FIRST",
                         self.gateway.selection_requests[-1]["requestedRepaymentOrder"])
        self.assertIsNotNone(eligible.json()["taskAction"])
        self.assertEqual("DOWNLOAD_B01_APPLICATION",
                         eligible.json()["b01DocumentAction"]["type"])
        self.assertEqual("/api/v1/customer/b01/application-form",
                         eligible.json()["b01DocumentAction"]["downloadPath"])

    def test_principal_first_loan_offers_interest_first_and_yes_requests_it(self) -> None:
        principal = candidate(503, "원금우선 대출", "210-***-000003")
        principal["repaymentOrder"] = "PRINCIPAL_FIRST"
        self.gateway.loans = [*self.gateway.loans, principal]
        self.post("principal-initial", "채무 순서 변경")

        selected = self.post("principal-loan", "3번")

        self.assertEqual(503, selected.json()["b01Entry"]["state"]["selected_loan_id"])
        self.assertIn("현재 충당순서는 원금 우선입니다", selected.json()["answer"])
        self.assertIn("이자 우선으로 변경하시겠어요?", selected.json()["answer"])
        self.assertEqual([], self.gateway.selection_requests)

        confirmed = self.post("principal-yes", "네")

        self.assertEqual("INTEREST_FIRST",
                         confirmed.json()["b01Entry"]["state"]["requested_repayment_order"])
        self.assertEqual("INTEREST_FIRST",
                         self.gateway.selection_requests[-1]["requestedRepaymentOrder"])

    def test_yes_after_an_information_question_still_answers_the_order_question(self) -> None:
        self.post("info-yes-initial", "채무 순서 변경")
        self.post("info-yes-loan", "1번")

        information = self.post("info-yes-question", "원금 우선이 뭐야?")
        self.assertEqual("INFORMATION_ONLY", information.json()["outcome"])
        self.assertEqual([], self.gateway.selection_requests)

        confirmed = self.post("info-yes-answer", "그래")

        self.assertEqual("TASK_ACTION_AVAILABLE", confirmed.json()["outcome"])
        self.assertEqual("PRINCIPAL_FIRST",
                         self.gateway.selection_requests[-1]["requestedRepaymentOrder"])

    def test_negative_or_hesitant_order_answer_does_not_change_the_selection(self) -> None:
        self.post("negative-initial", "채무 순서 변경")
        selected = self.post("negative-loan", "1번")
        question = selected.json()["answer"]

        for request_id, answer in (("negative-no", "아니"),
                                   ("negative-wait", "잠깐"),
                                   ("negative-later", "고민해볼게"),
                                   ("negative-cancel", "취소할래")):
            response = self.post(request_id, answer)
            self.assertEqual("REPAYMENT_ORDER_SELECTION",
                             response.json()["b01Entry"]["awaitingInput"])
            self.assertEqual(question, response.json()["answer"])

        self.assertEqual([], self.gateway.selection_requests)
        self.assertEqual([], self.gateway.eligibility_requests)

    def test_new_action_does_not_replay_completed_ineligible_checkpoint(self) -> None:
        self.gateway.eligible = False
        initial = self.post("old-ineligible-initial", "원금 우선으로 바꿔줘")
        self.assertEqual("LOAN_SELECTION", initial.json()["b01Entry"]["awaitingInput"])
        failed = self.post("old-ineligible-selection", "1번")
        self.assertEqual("INELIGIBLE",
                         failed.json()["b01Entry"]["state"]["eligibility_status"])
        eligibility_calls = len(self.gateway.eligibility_requests)
        self.gateway.prepare_outcome = "SAME_CHAT_REUSED"

        restarted = self.post("new-action-after-ineligible", "채무 순서 변경")

        self.assertEqual("LOAN_SELECTION", restarted.json()["b01Entry"]["awaitingInput"])
        self.assertEqual("SAME_CHAT_REUSED",
                         restarted.json()["b01Entry"]["prepareOutcome"])
        self.assertIsNone(restarted.json()["b01Entry"]["state"]["selected_loan_id"])
        self.assertIsNone(restarted.json()["b01Entry"]["state"]["eligibility_status"])
        self.assertIn("변경할 대출 번호를 입력해 주세요.", restarted.json()["answer"])
        self.assertNotIn("기한의 이익", restarted.json()["answer"])
        self.assertEqual(eligibility_calls, len(self.gateway.eligibility_requests))

    def test_missing_order_question_resumes_with_existing_loan(self) -> None:
        self.gateway.loans = [self.gateway.loans[0]]
        first = self.post("initial-order", "충당순서를 바꾸고 싶어")
        self.assertEqual("REPAYMENT_ORDER_SELECTION", first.json()["b01Entry"]["awaitingInput"])
        self.assertEqual(
            first.json()["b01Entry"]["state"]["pending_question"],
            first.json()["answer"],
        )
        self.assertTrue(first.json()["answer"].strip())

        resumed = self.post("followup-order", "이자 우선")

        self.assertEqual(501, resumed.json()["b01Entry"]["state"]["selected_loan_id"])
        self.assertEqual("INTEREST_FIRST", resumed.json()["b01Entry"]["state"]["requested_repayment_order"])

    def test_information_question_does_not_consume_pending_order_and_later_action_resumes(self) -> None:
        self.gateway.loans = [self.gateway.loans[0]]
        first = self.post("initial-info-during-graph", "충당순서를 바꾸고 싶어")
        before = first.json()["b01Entry"]

        information = self.post("pending-info", "원금 우선이 뭐야?")

        self.assertEqual("INFORMATION_ONLY", information.json()["outcome"])
        self.assertEqual("GENERATED", information.json()["answerStatus"])
        self.assertIn("원금 우선은", information.json()["answer"])
        self.assertEqual("REPAYMENT_ORDER_SELECTION",
                         information.json()["b01Entry"]["awaitingInput"])
        self.assertEqual(before["state"]["pending_question"],
                         information.json()["b01Entry"]["state"]["pending_question"])
        self.assertIsNone(information.json()["b01Entry"]["state"]["requested_repayment_order"])
        self.assertEqual(1, len(self.gateway.prepare_requests))
        self.assertEqual([], self.gateway.selection_requests)

        resumed = self.post("pending-action", "원금 우선으로 바꿀래")

        self.assertEqual("TASK_ACTION_AVAILABLE", resumed.json()["outcome"])
        self.assertEqual("PRINCIPAL_FIRST",
                         resumed.json()["b01Entry"]["state"]["requested_repayment_order"])
        self.assertEqual(501, resumed.json()["b01Entry"]["state"]["selected_loan_id"])
        self.assertEqual(1, len(self.gateway.prepare_requests))
        self.assertEqual(1, len(self.gateway.selection_requests))

    def test_clear_pending_selection_inputs_still_resume_without_classification(self) -> None:
        first = self.post("initial-clear-selection", "원금 우선으로 바꿔줘")
        classifier_calls = list(self.classifier.messages)

        resumed = self.post("clear-selection", "두 번째 걸로")

        self.assertEqual("LOAN_SELECTION", first.json()["b01Entry"]["awaitingInput"])
        self.assertEqual(502, resumed.json()["b01Entry"]["state"]["selected_loan_id"])
        self.assertEqual(classifier_calls, self.classifier.messages)

    def test_invalid_selection_returns_the_graph_question_again(self) -> None:
        first = self.post("initial-invalid", "원금 우선으로 바꿔줘")
        retried = self.post("followup-invalid", "9번")

        self.assertEqual("LOAN_SELECTION", first.json()["b01Entry"]["awaitingInput"])
        self.assertEqual("LOAN_SELECTION", retried.json()["b01Entry"]["awaitingInput"])
        self.assertEqual(
            retried.json()["b01Entry"]["state"]["pending_question"],
            retried.json()["answer"],
        )
        self.assertTrue(retried.json()["answer"].strip())

    def test_interrupt_without_question_is_rejected_as_a_contract_error(self) -> None:
        invalid_result = {
            "state": {
                "work_item_id": 1007,
                "pending_question": None,
                "response": "이 응답으로 대체하면 안 됩니다.",
            },
            "taskAction": None,
            "interrupted": True,
            "interrupt": None,
        }
        with patch(
            "app.customer_agent.router.B01GraphWorkflow.run",
            return_value=invalid_result,
        ):
            response = self.post(
                "missing-question",
                "원금 우선으로 바꿔줘",
                client=TestClient(self.client.app, raise_server_exceptions=False),
            )

        self.assertEqual(500, response.status_code)
        self.assertEqual("Internal Server Error", response.text)

    def test_ineligible_and_tool_error_never_return_task_action(self) -> None:
        self.gateway.eligible = False
        self.post("initial-ineligible", "원금 우선으로 바꿔줘")
        ineligible = self.post("followup-ineligible", "1번")
        self.assertIsNone(ineligible.json()["taskAction"])
        self.assertIn("기한의 이익", ineligible.json()["answer"])

        second_store = B01CheckpointStore(Path(self.temporary.name) / "error.sqlite3").open()
        try:
            self.gateway.loan_failure = True
            error = self.post("initial-error", "원금 우선으로 바꿔줘", chat_room_id=88,
                              client=self._client(second_store))
            self.assertIsNone(error.json()["taskAction"])
            self.assertEqual("FAILED", error.json()["answerStatus"])
            self.assertIn("오류", error.json()["answer"])
        finally:
            second_store.close()

    def test_same_request_retry_reuses_checkpoint_without_duplicate_tools(self) -> None:
        first = self.post("same-request", "원금 우선으로 바꿔줘")
        retry = self.post("same-request", "원금 우선으로 바꿔줘")

        self.assertEqual(first.json()["b01Entry"], retry.json()["b01Entry"])
        self.assertEqual(1, len(self.gateway.prepare_requests))
        self.assertEqual(1, len(self.gateway.loan_requests))

    def test_other_customer_cannot_resume_and_clear_other_task_is_not_consumed(self) -> None:
        self.post("initial-owner", "원금 우선으로 바꿔줘")

        other = self.post("other-customer", "2번", customer_id=8)
        self.assertEqual(200, other.status_code)
        self.assertEqual(1008, other.json()["b01Entry"]["state"]["work_item_id"])
        self.assertEqual(2, len(self.gateway.prepare_requests))

        transaction = self.post("other-task", "거래내역 조회해 줘")
        self.assertEqual("STARTED", transaction.json()["outcome"])
        self.assertEqual("TRANSACTION_SEARCH", transaction.json()["workflow"]["taskCode"])
        self.assertEqual(1, len(self.gateway.workflow_requests))

        resumed = self.post("owner-resume", "2번")
        self.assertEqual(502, resumed.json()["b01Entry"]["state"]["selected_loan_id"])

    def test_reopened_store_resumes_pending_router_conversation(self) -> None:
        self.post("initial-restart", "원금 우선으로 바꿔줘")
        self.store.close()

        reopened = B01CheckpointStore(self.path).open()
        try:
            new_client = self._client(reopened)
            resumed = self.post("followup-restart", "2번", client=new_client)
        finally:
            reopened.close()

        self.assertEqual(502, resumed.json()["b01Entry"]["state"]["selected_loan_id"])
        self.assertEqual(1, len(self.gateway.loan_requests))


if __name__ == "__main__":
    unittest.main()
