from __future__ import annotations

import hashlib
import re
from typing import Any, Literal, TypedDict

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator

from ..contracts import SpringToolGateway, TaskDefinition


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


class B01GraphRunResult(TypedDict):
    state: B01GraphState
    taskAction: dict[str, object] | None


class _LoanCandidateModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    loanId: int = Field(gt=0)
    loanName: str = Field(min_length=1, max_length=200)
    loanNoMasked: str = Field(min_length=3, max_length=100)
    repaymentOrder: Literal["INTEREST_FIRST", "PRINCIPAL_FIRST"]

    @field_validator("loanNoMasked")
    @classmethod
    def require_masked_number(cls, value: str) -> str:
        if "*" not in value:
            raise ValueError("B-01 loan number must be masked")
        return value


class _LoanCandidatesData(BaseModel):
    model_config = ConfigDict(extra="forbid")
    loans: list[_LoanCandidateModel]


class _SelectionData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workItemId: int = Field(gt=0)
    selectedLoanId: int = Field(gt=0)
    requestedRepaymentOrder: Literal["INTEREST_FIRST", "PRINCIPAL_FIRST"]
    changed: bool
    replayed: bool
    workItemVersion: int = Field(ge=0)


class _EligibilityData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workItemId: int = Field(gt=0)
    selectedLoanId: int = Field(gt=0)
    requestedRepaymentOrder: Literal["INTEREST_FIRST", "PRINCIPAL_FIRST"]
    eligibilityStatus: Literal["ELIGIBLE", "INELIGIBLE"]
    reasonCode: str = Field(min_length=1, max_length=100)
    reasonMessage: str = Field(min_length=1, max_length=500)


class _B01ToolEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requestId: str
    taskCode: Literal["REPAYMENT_ORDER_CHANGE"]
    workItemId: int = Field(gt=0)
    caseId: None
    revision: None
    data: dict[str, object]
    error: None


class _LoanCandidatesEnvelope(_B01ToolEnvelope):
    data: _LoanCandidatesData


class _SelectionEnvelope(_B01ToolEnvelope):
    data: _SelectionData

    @model_validator(mode="after")
    def validate_work_item(self) -> "_SelectionEnvelope":
        if self.workItemId != self.data.workItemId:
            raise ValueError("B-01 selection WorkItem fields do not match")
        return self


class _EligibilityEnvelope(_B01ToolEnvelope):
    data: _EligibilityData

    @model_validator(mode="after")
    def validate_work_item(self) -> "_EligibilityEnvelope":
        if self.workItemId != self.data.workItemId:
            raise ValueError("B-01 eligibility WorkItem fields do not match")
        return self


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


class B01GraphWorkflow:
    """B-01 대화 판단을 Spring의 검증된 Tool 결과에만 연결하는 독립 Graph."""

    REQUIRED_DOCUMENTS = ["채무변제 충당순서 변경 신청서(B01-01)"]

    def __init__(self, gateway: SpringToolGateway, customer_id: int,
                 operation_request_id: str) -> None:
        if customer_id <= 0 or not operation_request_id.strip():
            raise ValueError("B-01 Graph requires authenticated customer and request identifiers")
        self.gateway = gateway
        self.customer_id = customer_id
        self.operation_request_id = operation_request_id

    def run(self, state: B01GraphState) -> B01GraphRunResult:
        final_state = self._graph().invoke(dict(state))
        action = None
        if (final_state.get("eligibility_status") == "ELIGIBLE"
                and final_state.get("required_documents")
                and final_state.get("awaiting_input") is None):
            work_item_id = final_state["work_item_id"]
            action = {
                "type": "OPEN_B01_APPLICATION",
                "workItemId": work_item_id,
                "path": f"/tasks/b01/{work_item_id}",
            }
        return {"state": final_state, "taskAction": action}

    def _graph(self):
        from langgraph.graph import END, START, StateGraph

        graph = StateGraph(B01GraphState)
        graph.add_node("extract_b01_request", self.extract_b01_request)
        graph.add_node("resolve_target_loan", self.resolve_target_loan)
        graph.add_node("check_b01_eligibility", self.check_b01_eligibility)
        graph.add_node("prepare_required_docs", self.prepare_required_docs)
        graph.add_node("respond_b01", self.respond_b01)
        graph.add_edge(START, "extract_b01_request")
        graph.add_edge("extract_b01_request", "resolve_target_loan")
        graph.add_conditional_edges(
            "resolve_target_loan", self._after_resolve,
            {"check": "check_b01_eligibility", "respond": "respond_b01"})
        graph.add_conditional_edges(
            "check_b01_eligibility", self._after_eligibility,
            {"documents": "prepare_required_docs", "respond": "respond_b01"})
        graph.add_edge("prepare_required_docs", "respond_b01")
        graph.add_edge("respond_b01", END)
        return graph.compile()

    def extract_b01_request(self, state: B01GraphState) -> dict[str, object]:
        message = state["user_message"].strip()
        update: dict[str, object] = {
            "pending_question": None,
            "awaiting_input": None,
            "eligibility_status": None,
            "ineligible_reasons": [],
            "required_documents": [],
            "response": None,
        }
        if state.get("requested_repayment_order") is None:
            update["requested_repayment_order"] = self._extract_order(message)
        if state.get("loan_reference") is None:
            update["loan_reference"] = self._extract_loan_reference(message)
        return update

    def resolve_target_loan(self, state: B01GraphState) -> dict[str, object]:
        try:
            request_id = self._tool_request_id("loans")
            raw = self.gateway.b01_loans({
                "requestId": request_id,
                "customerId": self.customer_id,
                "workItemId": state["work_item_id"],
            })
            envelope = _LoanCandidatesEnvelope.model_validate(raw)
            self._require_response(envelope.requestId, request_id, envelope.workItemId,
                                   state["work_item_id"])
        except (RuntimeError, ValidationError, ValueError):
            return {"response": "대출 정보를 확인하는 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."}

        candidates: list[B01LoanCandidate] = [candidate.model_dump() for candidate in envelope.data.loans]
        if not candidates:
            return {"loan_candidates": [], "response": "변경 신청 대상이 될 수 있는 보유 대출이 없습니다."}

        selected_id = state.get("selected_loan_id")
        candidate_ids = {candidate["loanId"] for candidate in candidates}
        if selected_id is not None and selected_id not in candidate_ids:
            return self._loan_question(candidates, "선택한 대출을 현재 보유 대출에서 확인할 수 없습니다.")

        if selected_id is None:
            match, confirmation = self._match_candidate(state, candidates)
            if match is not None and not confirmation:
                selected_id = match["loanId"]
            elif match is not None:
                return {
                    "loan_candidates": candidates,
                    "selected_loan_id": match["loanId"],
                    "pending_question": (
                        f"{match['loanName']}({match['loanNoMasked']}) 대출을 변경 대상으로 선택할까요?"),
                    "awaiting_input": "LOAN_CONFIRMATION",
                }
            elif len(candidates) == 1:
                selected_id = candidates[0]["loanId"]
            else:
                return self._loan_question(candidates)

        requested_order = state.get("requested_repayment_order")
        if requested_order is None:
            return {
                "loan_candidates": candidates,
                "selected_loan_id": selected_id,
                "pending_question": "변경할 충당순서를 선택해 주세요. 원금 우선 또는 이자 우선 중 하나를 말씀해 주세요.",
                "awaiting_input": "REPAYMENT_ORDER_SELECTION",
            }

        try:
            request_id = self._tool_request_id("selection")
            raw = self.gateway.b01_selection({
                "requestId": request_id,
                "customerId": self.customer_id,
                "workItemId": state["work_item_id"],
                "selectedLoanId": selected_id,
                "requestedRepaymentOrder": requested_order,
            })
            envelope = _SelectionEnvelope.model_validate(raw)
            self._require_response(envelope.requestId, request_id, envelope.workItemId,
                                   state["work_item_id"])
            if (envelope.data.selectedLoanId != selected_id
                    or envelope.data.requestedRepaymentOrder != requested_order):
                raise ValueError("B-01 selection response differs from the request")
        except (RuntimeError, ValidationError, ValueError):
            return {
                "loan_candidates": candidates,
                "selected_loan_id": selected_id,
                "response": "선택 정보를 저장하지 못했습니다. 현재 신청 정보는 변경되지 않았습니다.",
            }
        return {
            "loan_candidates": candidates,
            "selected_loan_id": selected_id,
            "pending_question": None,
            "awaiting_input": None,
        }

    def check_b01_eligibility(self, state: B01GraphState) -> dict[str, object]:
        try:
            request_id = self._tool_request_id("eligibility")
            raw = self.gateway.b01_eligibility({
                "requestId": request_id,
                "customerId": self.customer_id,
                "workItemId": state["work_item_id"],
            })
            envelope = _EligibilityEnvelope.model_validate(raw)
            self._require_response(envelope.requestId, request_id, envelope.workItemId,
                                   state["work_item_id"])
            if (envelope.data.selectedLoanId != state.get("selected_loan_id")
                    or envelope.data.requestedRepaymentOrder != state.get("requested_repayment_order")):
                raise ValueError("B-01 eligibility response differs from the saved selection")
        except (RuntimeError, ValidationError, ValueError):
            return {"response": "변경 가능 여부를 확인하는 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."}

        if envelope.data.eligibilityStatus == "INELIGIBLE":
            return {
                "eligibility_status": "INELIGIBLE",
                "ineligible_reasons": [envelope.data.reasonCode, envelope.data.reasonMessage],
                "response": f"현재 선택한 대출은 변경 신청이 어렵습니다. {envelope.data.reasonMessage}",
            }
        return {
            "eligibility_status": "ELIGIBLE",
            "ineligible_reasons": [],
        }

    def prepare_required_docs(self, state: B01GraphState) -> dict[str, object]:
        if state.get("eligibility_status") != "ELIGIBLE":
            return {}
        return {"required_documents": list(self.REQUIRED_DOCUMENTS)}

    def respond_b01(self, state: B01GraphState) -> dict[str, object]:
        if state.get("pending_question"):
            return {"response": state["pending_question"]}
        if state.get("response"):
            return {}
        if state.get("eligibility_status") == "ELIGIBLE":
            return {"response": (
                "대출 규칙 사전검증을 통과했습니다. 채무변제 충당순서 변경 신청서(B01-01)를 "
                "준비해 신청 화면에서 제출해 주세요. 이후 서류 검증, 필수 동의, 모의서명과 "
                "SMS 본인확인이 필요하며, 이 안내는 최종 변경 승인을 의미하지 않습니다.")}
        return {"response": "B-01 신청 정보를 확인하지 못했습니다. 다시 요청해 주세요."}

    @staticmethod
    def _after_resolve(state: B01GraphState) -> Literal["check", "respond"]:
        if (state.get("response") is None and state.get("awaiting_input") is None
                and state.get("selected_loan_id") is not None
                and state.get("requested_repayment_order") is not None):
            return "check"
        return "respond"

    @staticmethod
    def _after_eligibility(state: B01GraphState) -> Literal["documents", "respond"]:
        return "documents" if state.get("eligibility_status") == "ELIGIBLE" else "respond"

    @staticmethod
    def _extract_order(message: str) -> Literal["INTEREST_FIRST", "PRINCIPAL_FIRST"] | None:
        compact = re.sub(r"\s+", "", message)
        principal = any(term in compact for term in ("원금부터", "원금우선", "비용원금이자"))
        interest = any(term in compact for term in ("이자부터", "이자우선", "비용이자원금"))
        if principal == interest:
            return None
        return "PRINCIPAL_FIRST" if principal else "INTEREST_FIRST"

    @staticmethod
    def _extract_loan_reference(message: str) -> str | None:
        quoted = re.search(r"[\"'“‘]([^\"'”’]{2,100})[\"'”’]", message)
        if quoted:
            return quoted.group(1).strip()
        masked = re.search(r"[0-9*]{2,}(?:-[0-9*]{2,}){1,4}", message)
        if masked:
            return masked.group(0)
        product = re.search(r"([0-9A-Za-z가-힣]+\s*대출)", message)
        return product.group(1).strip() if product else None

    @classmethod
    def _match_candidate(cls, state: B01GraphState,
                         candidates: list[B01LoanCandidate]) -> tuple[B01LoanCandidate | None, bool]:
        message = cls._normalize(state["user_message"])
        reference = cls._normalize(state.get("loan_reference") or "")
        exact = [candidate for candidate in candidates
                 if cls._normalize(candidate["loanName"]) in message
                 or cls._normalize(candidate["loanNoMasked"]) in message]
        if len(exact) == 1:
            return exact[0], False
        if reference:
            reference_matches = [candidate for candidate in candidates
                                 if reference == cls._normalize(candidate["loanName"])
                                 or reference == cls._normalize(candidate["loanNoMasked"])]
            if len(reference_matches) == 1:
                return reference_matches[0], False
            partial = [candidate for candidate in candidates
                       if reference in cls._normalize(candidate["loanName"])]
            if len(partial) == 1:
                return partial[0], True
        return None, False

    @staticmethod
    def _normalize(value: str) -> str:
        return re.sub(r"[^0-9A-Za-z가-힣*]", "", value).lower()

    @staticmethod
    def _loan_question(candidates: list[B01LoanCandidate], prefix: str | None = None) -> dict[str, object]:
        choices = ", ".join(
            f"{candidate['loanName']}({candidate['loanNoMasked']})" for candidate in candidates)
        question = f"{choices} 중 변경할 대출을 선택해 주세요."
        if prefix:
            question = f"{prefix} {question}"
        return {
            "loan_candidates": candidates,
            "selected_loan_id": None,
            "pending_question": question,
            "awaiting_input": "LOAN_SELECTION",
        }

    def _tool_request_id(self, stage: str) -> str:
        digest = hashlib.sha256(self.operation_request_id.encode("utf-8")).hexdigest()[:32]
        return f"b01-{stage}-{digest}"

    @staticmethod
    def _require_response(actual_request_id: str, expected_request_id: str,
                          actual_work_item_id: int, expected_work_item_id: int) -> None:
        if (actual_request_id != expected_request_id
                or actual_work_item_id != expected_work_item_id):
            raise ValueError("B-01 Tool response does not match the request")


class RepaymentOrderChangeTask:
    """Registers B-01 without granting the Agent execution authority."""

    definition = TaskDefinition("REPAYMENT_ORDER_CHANGE", "채무변제 충당순서 변경", ())

    def run(self, request: dict[str, Any]) -> dict[str, Any]:
        return {"taskCode": self.definition.task_code, "screenOnly": True}
