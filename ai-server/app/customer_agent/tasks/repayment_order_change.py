from __future__ import annotations

from typing import Any, Literal, TypedDict

from ..contracts import TaskDefinition


class B01LoanCandidate(TypedDict):
    """Spring Tool에서 검증된 대출 후보만 담는 상태 계약."""

    loanId: int
    loanName: str
    loanNoMasked: str
    repaymentOrder: str


B01AwaitingInput = Literal[
    "LOAN_SELECTION",
    "LOAN_CONFIRMATION",
    "REPAYMENT_ORDER_SELECTION",
] | None


class B01GraphState(TypedDict):
    work_item_id: int
    chat_room_id: int
    user_message: str
    pending_question: str | None
    awaiting_input: B01AwaitingInput
    loan_reference: str | None
    loan_candidates: list[B01LoanCandidate]
    selected_loan_id: int | None
    requested_repayment_order: Literal["INTEREST_FIRST", "PRINCIPAL_FIRST"] | None
    eligibility_status: Literal["ELIGIBLE", "INELIGIBLE"] | None
    ineligible_reasons: list[str]
    required_documents: list[str]
    response: str | None


def initialize_b01_state(work_item_id: int, chat_room_id: int,
                         user_message: str) -> B01GraphState:
    if work_item_id <= 0 or chat_room_id <= 0:
        raise ValueError("B-01 state requires positive WorkItem and chat room identifiers")
    return {
        "work_item_id": work_item_id,
        "chat_room_id": chat_room_id,
        "user_message": user_message,
        "pending_question": None,
        "awaiting_input": None,
        "loan_reference": None,
        "loan_candidates": [],
        "selected_loan_id": None,
        "requested_repayment_order": None,
        "eligibility_status": None,
        "ineligible_reasons": [],
        "required_documents": [],
        "response": None,
    }


def b01_thread_id(work_item_id: int) -> str:
    if work_item_id <= 0:
        raise ValueError("B-01 thread requires a positive WorkItem identifier")
    return f"b01:{work_item_id}"


class RepaymentOrderChangeTask:
    """Registers B-01 without granting the Agent execution authority."""

    definition = TaskDefinition("REPAYMENT_ORDER_CHANGE", "채무변제 충당순서 변경", ())

    def run(self, request: dict[str, Any]) -> dict[str, Any]:
        return {"taskCode": self.definition.task_code, "screenOnly": True}
