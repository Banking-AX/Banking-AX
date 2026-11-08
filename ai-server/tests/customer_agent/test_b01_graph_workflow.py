from __future__ import annotations

import unittest
from typing import Any
from unittest.mock import patch

from app.customer_agent.tasks.repayment_order_change import (
    B01GraphWorkflow,
    initialize_b01_state,
)
from app.customer_agent.tool_gateway import SpringHttpToolGateway


def loan(loan_id: int, name: str, masked: str) -> dict[str, object]:
    return {"loanId": loan_id, "loanName": name, "loanNoMasked": masked,
            "repaymentOrder": "INTEREST_FIRST"}


class B01GatewayStub:
    def __init__(self, loans: list[dict[str, object]]) -> None:
        self.loans = loans
        self.loan_requests: list[dict[str, object]] = []
        self.selection_requests: list[dict[str, object]] = []
        self.eligibility_requests: list[dict[str, object]] = []
        self.selection_failure = False
        self.eligibility_failure = False
        self.loan_failure = False
        self.eligibility_status = "ELIGIBLE"
        self.reason_code = "ELIGIBLE"
        self.reason_message = "대출 규칙 사전검증을 통과했습니다."

    def b01_loans(self, request: dict[str, Any]) -> dict[str, Any]:
        self.loan_requests.append(request)
        if self.loan_failure:
            raise RuntimeError("SPRING_TOOL_UNAVAILABLE")
        return self._envelope(request, {"loans": self.loans})

    def b01_selection(self, request: dict[str, Any]) -> dict[str, Any]:
        self.selection_requests.append(request)
        if self.selection_failure:
            raise RuntimeError("SPRING_TOOL_HTTP_409")
        return self._envelope(request, {
            "workItemId": request["workItemId"],
            "selectedLoanId": request["selectedLoanId"],
            "requestedRepaymentOrder": request["requestedRepaymentOrder"],
            "changed": True,
            "replayed": False,
            "workItemVersion": 1,
        })

    def b01_eligibility(self, request: dict[str, Any]) -> dict[str, Any]:
        self.eligibility_requests.append(request)
        if self.eligibility_failure:
            raise RuntimeError("SPRING_TOOL_UNAVAILABLE")
        selection = self.selection_requests[-1]
        return self._envelope(request, {
            "workItemId": request["workItemId"],
            "selectedLoanId": selection["selectedLoanId"],
            "requestedRepaymentOrder": selection["requestedRepaymentOrder"],
            "eligibilityStatus": self.eligibility_status,
            "reasonCode": self.reason_code,
            "reasonMessage": self.reason_message,
        })

    @staticmethod
    def _envelope(request: dict[str, Any], data: dict[str, Any]) -> dict[str, Any]:
        return {
            "requestId": request["requestId"],
            "taskCode": "REPAYMENT_ORDER_CHANGE",
            "workItemId": request["workItemId"],
            "caseId": None,
            "revision": None,
            "data": data,
            "error": None,
        }


class B01GraphWorkflowTests(unittest.TestCase):
    def run_graph(self, gateway: B01GatewayStub, message: str,
                  **state_updates: object) -> dict[str, Any]:
        state = initialize_b01_state(1001, 77, message)
        state.update(state_updates)  # type: ignore[typeddict-item]
        return B01GraphWorkflow(gateway, 7, "operation-b01-001").run(state)

    def test_graph_has_exactly_the_five_business_nodes(self) -> None:
        gateway = B01GatewayStub([])
        graph = B01GraphWorkflow(gateway, 7, "operation-b01-001")._graph().get_graph()

        business_nodes = set(graph.nodes) - {"__start__", "__end__"}
        self.assertEqual({
            "extract_b01_request",
            "resolve_target_loan",
            "check_b01_eligibility",
            "prepare_required_docs",
            "respond_b01",
        }, business_nodes)

    def test_no_loans_returns_customer_message_without_selection_or_action(self) -> None:
        gateway = B01GatewayStub([])
        result = self.run_graph(gateway, "원금부터 갚도록 바꿔줘")

        self.assertIn("보유 대출이 없습니다", result["state"]["response"])
        self.assertIsNone(result["taskAction"])
        self.assertEqual([], gateway.selection_requests)
        self.assertEqual([], gateway.eligibility_requests)

    def test_single_loan_and_order_runs_selection_and_eligibility(self) -> None:
        gateway = B01GatewayStub([loan(501, "신한 직장인대출", "210-***-000001")])
        result = self.run_graph(gateway, "원금부터 갚는 순서로 바꿔줘")

        state = result["state"]
        self.assertEqual(501, state["selected_loan_id"])
        self.assertEqual("PRINCIPAL_FIRST", state["requested_repayment_order"])
        self.assertEqual("ELIGIBLE", state["eligibility_status"])
        self.assertEqual(["채무변제 충당순서 변경 신청서(B01-01)"], state["required_documents"])
        self.assertEqual("/tasks/b01/1001", result["taskAction"]["path"])
        self.assertEqual(501, gateway.selection_requests[0]["selectedLoanId"])
        self.assertEqual(7, gateway.selection_requests[0]["customerId"])
        self.assertLessEqual(len(gateway.selection_requests[0]["requestId"]), 64)

    def test_multiple_loans_without_reference_asks_for_selection(self) -> None:
        gateway = B01GatewayStub([
            loan(501, "직장인대출", "210-***-000001"),
            loan(502, "주택담보대출", "210-***-000002"),
        ])
        result = self.run_graph(gateway, "원금부터 갚게 바꿔줘")

        self.assertEqual("LOAN_SELECTION", result["state"]["awaiting_input"])
        self.assertIn("직장인대출(210-***-000001)", result["state"]["pending_question"])
        self.assertIsNone(result["taskAction"])
        self.assertEqual([], gateway.selection_requests)

    def test_product_name_in_message_selects_only_a_spring_candidate(self) -> None:
        gateway = B01GatewayStub([
            loan(501, "직장인대출", "210-***-000001"),
            loan(502, "주택담보대출", "210-***-000002"),
        ])
        result = self.run_graph(gateway, "주택담보대출을 원금 우선으로 바꿔줘")

        self.assertEqual("주택담보대출", result["state"]["loan_reference"])
        self.assertEqual(502, result["state"]["selected_loan_id"])
        self.assertEqual(502, gateway.selection_requests[0]["selectedLoanId"])

    def test_partial_loan_reference_requires_confirmation(self) -> None:
        gateway = B01GatewayStub([
            loan(501, "신한 직장인대출", "210-***-000001"),
            loan(502, "청년 전세대출", "210-***-000002"),
        ])
        result = self.run_graph(
            gateway, "원금 우선으로 바꿔줘", loan_reference="직장인")

        self.assertEqual("LOAN_CONFIRMATION", result["state"]["awaiting_input"])
        self.assertEqual(501, result["state"]["selected_loan_id"])
        self.assertIsNone(result["taskAction"])

    def test_missing_repayment_order_asks_after_resolving_single_loan(self) -> None:
        gateway = B01GatewayStub([loan(501, "직장인대출", "210-***-000001")])
        result = self.run_graph(gateway, "충당순서를 바꾸고 싶어")

        self.assertEqual("REPAYMENT_ORDER_SELECTION", result["state"]["awaiting_input"])
        self.assertEqual(501, result["state"]["selected_loan_id"])
        self.assertEqual([], gateway.selection_requests)
        self.assertIsNone(result["taskAction"])

    def test_selection_failure_stops_before_eligibility(self) -> None:
        gateway = B01GatewayStub([loan(501, "직장인대출", "210-***-000001")])
        gateway.selection_failure = True
        result = self.run_graph(gateway, "원금부터 갚게 바꿔줘")

        self.assertIn("선택 정보를 저장하지 못했습니다", result["state"]["response"])
        self.assertEqual([], gateway.eligibility_requests)
        self.assertIsNone(result["taskAction"])

    def test_ineligible_uses_only_spring_reason_and_has_no_page_action(self) -> None:
        gateway = B01GatewayStub([loan(501, "직장인대출", "210-***-000001")])
        gateway.eligibility_status = "INELIGIBLE"
        gateway.reason_code = "NOT_ACCELERATED"
        gateway.reason_message = "기한의 이익이 상실된 대출만 신청할 수 있습니다."
        result = self.run_graph(gateway, "원금부터 갚게 바꿔줘")

        self.assertEqual("INELIGIBLE", result["state"]["eligibility_status"])
        self.assertEqual(["NOT_ACCELERATED", gateway.reason_message],
                         result["state"]["ineligible_reasons"])
        self.assertIn(gateway.reason_message, result["state"]["response"])
        self.assertIsNone(result["taskAction"])
        self.assertEqual([], result["state"]["required_documents"])

    def test_eligibility_tool_failure_is_not_reported_as_ineligible(self) -> None:
        gateway = B01GatewayStub([loan(501, "직장인대출", "210-***-000001")])
        gateway.eligibility_failure = True
        result = self.run_graph(
            gateway,
            "원금부터 갚게 바꿔줘",
            eligibility_status="ELIGIBLE",
            required_documents=["이전 문서"],
        )

        self.assertIsNone(result["state"]["eligibility_status"])
        self.assertEqual([], result["state"]["required_documents"])
        self.assertIn("오류가 발생했습니다", result["state"]["response"])
        self.assertIsNone(result["taskAction"])

    def test_customer_text_cannot_invent_a_loan_id(self) -> None:
        gateway = B01GatewayStub([
            loan(501, "직장인대출", "210-***-000001"),
            loan(502, "주택담보대출", "210-***-000002"),
        ])
        result = self.run_graph(gateway, "999번 대출을 원금 우선으로 바꿔줘")

        self.assertEqual("LOAN_SELECTION", result["state"]["awaiting_input"])
        self.assertIsNone(result["state"]["selected_loan_id"])
        self.assertEqual([], gateway.selection_requests)

    def test_preconfirmed_id_must_exist_in_spring_candidates(self) -> None:
        gateway = B01GatewayStub([loan(501, "직장인대출", "210-***-000001")])
        result = self.run_graph(
            gateway, "원금 우선으로 바꿔줘", selected_loan_id=999)

        self.assertEqual("LOAN_SELECTION", result["state"]["awaiting_input"])
        self.assertIsNone(result["state"]["selected_loan_id"])
        self.assertEqual([], gateway.selection_requests)


class B01GraphHttpGatewayTests(unittest.TestCase):
    @patch.dict("os.environ", {"SPRING_TOOL_GATEWAY_TOKEN": "test-gateway-token"})
    def test_calls_the_three_existing_b01_tool_paths_with_customer_auth(self) -> None:
        gateway = SpringHttpToolGateway("http://spring.invalid")
        common = {"customerId": 7, "workItemId": 1001}

        with patch.object(gateway, "_post", return_value={}) as post:
            gateway.b01_loans({**common, "requestId": "loans-id"})
            gateway.b01_selection({
                **common,
                "requestId": "selection-id",
                "selectedLoanId": 501,
                "requestedRepaymentOrder": "PRINCIPAL_FIRST",
            })
            gateway.b01_eligibility({**common, "requestId": "eligibility-id"})

        headers = {"Content-Type": "application/json", "X-Actor-Id": "7",
                   "X-Actor-Role": "CUSTOMER", "X-Tool-Gateway-Token": "test-gateway-token"}
        self.assertEqual([
            (("/api/v1/tools/b01/loans",
              {"requestId": "loans-id", "workItemId": 1001}, headers), {}),
            (("/api/v1/tools/b01/selection",
              {"requestId": "selection-id", "workItemId": 1001,
               "selectedLoanId": 501, "requestedRepaymentOrder": "PRINCIPAL_FIRST"}, headers), {}),
            (("/api/v1/tools/b01/eligibility",
              {"requestId": "eligibility-id", "workItemId": 1001}, headers), {}),
        ], [(call.args, call.kwargs) for call in post.call_args_list])


if __name__ == "__main__":
    unittest.main()
