from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from typing import Any

from app.customer_agent.b01_checkpoint import B01CheckpointStore
from app.customer_agent.tasks.repayment_order_change import (
    B01GraphWorkflow,
    initialize_b01_state,
)


def loan(loan_id: int, name: str, masked: str) -> dict[str, object]:
    return {"loanId": loan_id, "loanName": name, "loanNoMasked": masked,
            "repaymentOrder": "INTEREST_FIRST"}


class CheckpointGateway:
    def __init__(self, loans: list[dict[str, object]]) -> None:
        self.loans = loans
        self.loan_requests: list[dict[str, object]] = []
        self.selection_requests: list[dict[str, object]] = []
        self.eligibility_requests: list[dict[str, object]] = []
        self.eligible = True

    def b01_loans(self, request: dict[str, Any]) -> dict[str, Any]:
        self.loan_requests.append(request)
        return self._envelope(request, {"loans": self.loans})

    def b01_selection(self, request: dict[str, Any]) -> dict[str, Any]:
        self.selection_requests.append(request)
        return self._envelope(request, {
            "workItemId": request["workItemId"],
            "selectedLoanId": request["selectedLoanId"],
            "requestedRepaymentOrder": request["requestedRepaymentOrder"],
            "changed": True, "replayed": False, "workItemVersion": 1,
        })

    def b01_eligibility(self, request: dict[str, Any]) -> dict[str, Any]:
        self.eligibility_requests.append(request)
        selection = self.selection_requests[-1]
        return self._envelope(request, {
            "workItemId": request["workItemId"],
            "selectedLoanId": selection["selectedLoanId"],
            "requestedRepaymentOrder": selection["requestedRepaymentOrder"],
            "eligibilityStatus": "ELIGIBLE" if self.eligible else "INELIGIBLE",
            "reasonCode": "ELIGIBLE" if self.eligible else "NOT_ACCELERATED",
            "reasonMessage": ("대출 규칙 사전검증을 통과했습니다."
                              if self.eligible else "기한의 이익이 상실된 대출만 신청할 수 있습니다."),
        })

    @staticmethod
    def _envelope(request: dict[str, Any], data: dict[str, Any]) -> dict[str, Any]:
        return {"requestId": request["requestId"], "taskCode": "REPAYMENT_ORDER_CHANGE",
                "workItemId": request["workItemId"], "caseId": None, "revision": None,
                "data": data, "error": None}


class B01CheckpointResumeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temporary.name) / "b01.sqlite3"
        self.loans = [
            loan(501, "직장인대출", "210-***-000001"),
            loan(502, "주택담보대출", "210-***-000002"),
        ]

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def workflow(self, gateway: CheckpointGateway, store: B01CheckpointStore,
                 request_id: str = "operation-initial") -> B01GraphWorkflow:
        return B01GraphWorkflow(gateway, 7, request_id, store.saver)

    def test_multiple_loans_interrupt_and_second_choice_resumes_without_relookup(self) -> None:
        gateway = CheckpointGateway(self.loans)
        with B01CheckpointStore(self.db_path) as store:
            workflow = self.workflow(gateway, store)
            first = workflow.run(initialize_b01_state(1001, 77, "원금 우선으로 바꿔줘"))

            self.assertTrue(first["interrupted"])
            self.assertEqual("LOAN_SELECTION", first["interrupt"]["awaitingInput"])
            self.assertEqual(2, len(first["interrupt"]["loanCandidates"]))

            resumed = workflow.resume(1001, "2번")

        self.assertFalse(resumed["interrupted"])
        self.assertEqual(502, resumed["state"]["selected_loan_id"])
        self.assertEqual("ELIGIBLE", resumed["state"]["eligibility_status"])
        self.assertEqual("/tasks/b01/1001", resumed["taskAction"]["path"])
        self.assertEqual(1, len(gateway.loan_requests))
        self.assertEqual(1, len(gateway.selection_requests))
        self.assertEqual(1, len(gateway.eligibility_requests))

    def test_invalid_selection_interrupts_again_without_tool_side_effects(self) -> None:
        gateway = CheckpointGateway(self.loans)
        with B01CheckpointStore(self.db_path) as store:
            workflow = self.workflow(gateway, store)
            workflow.run(initialize_b01_state(1001, 77, "원금 우선으로 바꿔줘"))
            invalid = workflow.resume(1001, "9번")

            self.assertTrue(invalid["interrupted"])
            self.assertEqual("LOAN_SELECTION", invalid["interrupt"]["awaitingInput"])
            self.assertIn("확인할 수 없습니다", invalid["interrupt"]["question"])
            self.assertEqual([], gateway.selection_requests)
            self.assertEqual([], gateway.eligibility_requests)

            completed = workflow.resume(1001, "1번")

        self.assertFalse(completed["interrupted"])
        self.assertEqual(501, completed["state"]["selected_loan_id"])
        self.assertEqual(1, len(gateway.loan_requests))

    def test_confirmation_yes_confirms_existing_candidate(self) -> None:
        gateway = CheckpointGateway(self.loans)
        state = initialize_b01_state(1001, 77, "원금 우선으로 바꿔줘")
        state["loan_reference"] = "직장인"
        with B01CheckpointStore(self.db_path) as store:
            workflow = self.workflow(gateway, store)
            first = workflow.run(state)
            self.assertEqual("LOAN_CONFIRMATION", first["interrupt"]["awaitingInput"])

            completed = workflow.resume(1001, "예")

        self.assertFalse(completed["interrupted"])
        self.assertEqual(501, completed["state"]["selected_loan_id"])

    def test_confirmation_no_returns_to_loan_selection(self) -> None:
        gateway = CheckpointGateway(self.loans)
        state = initialize_b01_state(1001, 77, "원금 우선으로 바꿔줘")
        state["loan_reference"] = "직장인"
        with B01CheckpointStore(self.db_path) as store:
            workflow = self.workflow(gateway, store)
            workflow.run(state)
            rejected = workflow.resume(1001, "아니요")

            self.assertTrue(rejected["interrupted"])
            self.assertEqual("LOAN_SELECTION", rejected["interrupt"]["awaitingInput"])
            self.assertIsNone(rejected["state"]["selected_loan_id"])

            completed = workflow.resume(1001, "2번")

        self.assertEqual(502, completed["state"]["selected_loan_id"])

    def test_missing_order_interrupts_and_preserves_selected_loan(self) -> None:
        gateway = CheckpointGateway([self.loans[0]])
        with B01CheckpointStore(self.db_path) as store:
            workflow = self.workflow(gateway, store)
            first = workflow.run(initialize_b01_state(1001, 77, "충당순서를 변경하고 싶어"))

            self.assertTrue(first["interrupted"])
            self.assertEqual("REPAYMENT_ORDER_SELECTION", first["interrupt"]["awaitingInput"])
            self.assertEqual(501, first["state"]["selected_loan_id"])

            invalid = workflow.resume(1001, "잘 모르겠어")
            self.assertTrue(invalid["interrupted"])
            self.assertEqual(501, invalid["state"]["selected_loan_id"])

            completed = workflow.resume(1001, "이자 우선")

        self.assertEqual(501, completed["state"]["selected_loan_id"])
        self.assertEqual("INTEREST_FIRST", completed["state"]["requested_repayment_order"])

    def test_store_reopen_restores_checkpoint_after_simulated_restart(self) -> None:
        first_gateway = CheckpointGateway(self.loans)
        with B01CheckpointStore(self.db_path) as first_store:
            first = self.workflow(first_gateway, first_store).run(
                initialize_b01_state(1001, 77, "원금 우선으로 바꿔줘"))
            self.assertTrue(first["interrupted"])
        self.assertEqual(0o600, os.stat(self.db_path).st_mode & 0o777)

        second_gateway = CheckpointGateway(self.loans)
        with B01CheckpointStore(self.db_path) as second_store:
            restored = self.workflow(second_gateway, second_store, "operation-resume").resume(1001, "2번")

        self.assertEqual(502, restored["state"]["selected_loan_id"])
        self.assertEqual([], second_gateway.loan_requests)
        self.assertEqual(1, len(second_gateway.selection_requests))

    def test_same_start_and_resume_requests_do_not_repeat_tools(self) -> None:
        gateway = CheckpointGateway(self.loans)
        initial = initialize_b01_state(1001, 77, "원금 우선으로 바꿔줘")
        with B01CheckpointStore(self.db_path) as store:
            workflow = self.workflow(gateway, store)
            first = workflow.run(initial)
            retry = workflow.run(initial)
            self.assertEqual(first["interrupt"], retry["interrupt"])
            self.assertEqual(1, len(gateway.loan_requests))

            completed = workflow.resume(1001, "2번")
            completed_retry = workflow.resume(1001, "2번")

        self.assertEqual(completed["state"], completed_retry["state"])
        self.assertEqual(1, len(gateway.selection_requests))
        self.assertEqual(1, len(gateway.eligibility_requests))

    def test_work_item_threads_are_isolated(self) -> None:
        gateway = CheckpointGateway(self.loans)
        with B01CheckpointStore(self.db_path) as store:
            first_workflow = self.workflow(gateway, store, "operation-1001")
            second_workflow = self.workflow(gateway, store, "operation-1002")
            first_workflow.run(initialize_b01_state(1001, 77, "원금 우선으로 바꿔줘"))
            second_workflow.run(initialize_b01_state(1002, 78, "원금 우선으로 바꿔줘"))

            first_done = first_workflow.resume(1001, "1번")
            second_still_waiting = second_workflow.run(
                initialize_b01_state(1002, 78, "원금 우선으로 바꿔줘"))

        self.assertEqual(501, first_done["state"]["selected_loan_id"])
        self.assertTrue(second_still_waiting["interrupted"])
        self.assertIsNone(second_still_waiting["state"]["selected_loan_id"])
        self.assertEqual({1001, 1002}, {request["workItemId"] for request in gateway.loan_requests})

    def test_ineligible_resume_has_no_page_action(self) -> None:
        gateway = CheckpointGateway(self.loans)
        gateway.eligible = False
        with B01CheckpointStore(self.db_path) as store:
            workflow = self.workflow(gateway, store)
            workflow.run(initialize_b01_state(1001, 77, "원금 우선으로 바꿔줘"))
            result = workflow.resume(1001, "1번")

        self.assertEqual("INELIGIBLE", result["state"]["eligibility_status"])
        self.assertIsNone(result["taskAction"])


if __name__ == "__main__":
    unittest.main()
