from __future__ import annotations

import os
import hmac
from decimal import Decimal
from typing import Literal

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator
from .contracts import SpringToolGateway
from .registry import TaskRegistry
from .tasks.loan_inquiry import LoanInquiryAnswerTask, LoanInquiryTask
from .tasks.transaction_search import TransactionSearchTask
from .tasks.housing_savings_close import HousingSavingsCloseTask
from .tasks.debt_adjustment import DebtAdjustmentTask
from .tasks.repayment_order_change import RepaymentOrderChangeTask
from app.tasks.c02 import InheritanceDepositTask, SpringC02ValidationGateway
from .tool_gateway import SpringHttpToolGateway, SpringToolHttpError
from .intent_classifier import (
    IntentClassifier,
    IntentClassification,
    IntentOutputValidationError,
    IntentProviderError,
    OpenAiIntentClient,
    RequestKind,
    TaskCode,
)


class CustomerTaskRequest(BaseModel):
    taskCode: str
    requestId: str = Field(min_length=1, max_length=90)
    workItemId: int | None = None
    customerId: int
    loanId: int | None = None
    accountId: int | None = None
    startDate: str | None = None
    endDate: str | None = None
    transactionType: str | None = None
    minimumAmount: str | None = None
    maximumAmount: str | None = None
    keyword: str | None = None
    sortOrder: str | None = None
    page: int | None = None
    size: int | None = None
    workflowId: int | None = None
    expectedWorkflowVersion: int = 0
    registeredPhone: str | None = None
    taxed: bool | None = None
    waivePreferredRate: bool = False
    consented: bool = False
    mockSignatureCompleted: bool = False
    bindingId: str | None = None
    version: int = 0
    decedent: dict[str, object] | None = None
    heirs: list[dict[str, object]] = Field(default_factory=list)
    documentStatuses: dict[str, str] = Field(default_factory=dict)
    submissionConfirmed: bool = False
    additionalInput: dict[str, object] = Field(default_factory=dict)


class CustomerIntentRequest(BaseModel):
    message: str = Field(min_length=2, max_length=2000)
    recentMessages: list[str] = Field(default_factory=list, max_length=5)


class CustomerNaturalStartRequest(CustomerIntentRequest):
    requestId: str = Field(min_length=1, max_length=90)
    customerId: int = Field(gt=0)
    chatRoomId: int = Field(gt=0)


class WorkflowStartData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workflowId: int = Field(gt=0)
    workItemId: int = Field(gt=0)
    taskCode: TaskCode
    status: Literal["ACTIVE", "DONE", "COMPLETED"]


class WorkflowStartEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requestId: str
    taskCode: TaskCode
    workItemId: int = Field(gt=0)
    caseId: None
    revision: None
    data: WorkflowStartData
    error: None

    @model_validator(mode="after")
    def validate_consistency(self) -> "WorkflowStartEnvelope":
        if self.workItemId != self.data.workItemId or self.taskCode != self.data.taskCode:
            raise ValueError("workflow start envelope fields do not match")
        return self


class LoanSummary(BaseModel):
    model_config = ConfigDict(extra="forbid")

    loanId: int = Field(gt=0)
    loanNoMasked: str = Field(min_length=3, max_length=100)
    loanName: str = Field(min_length=1, max_length=200)
    balance: Decimal
    interestRate: Decimal
    overdueDays: int = Field(ge=0)
    accelerationDeclared: bool
    repaymentOrder: str = Field(min_length=1, max_length=100)

    @field_validator("loanNoMasked")
    @classmethod
    def require_masked_loan_number(cls, value: str) -> str:
        if "***" not in value:
            raise ValueError("loan number must be masked")
        return value


class LoanInquiryData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    loans: list[LoanSummary]
    workItemStatus: Literal["DONE"] | None = None
    workflowStatus: Literal["COMPLETED"] | None = None


class LoanInquiryEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requestId: str
    taskCode: Literal["LOAN_INQUIRY"]
    workItemId: int = Field(gt=0)
    caseId: None
    revision: None
    data: LoanInquiryData
    error: None


class ExecutionStatus(BaseModel):
    workflowId: int = Field(gt=0)
    workItemId: int = Field(gt=0)
    taskCode: Literal["LOAN_INQUIRY"]
    workItemStatus: Literal["DONE"]
    workflowStatus: Literal["COMPLETED"]


class CustomerTaskAction(BaseModel):
    taskCode: Literal["REPAYMENT_ORDER_CHANGE"]
    label: Literal["신청 화면으로 이동"] = "신청 화면으로 이동"
    actionRequestId: str = Field(min_length=1, max_length=90)


class CustomerNaturalStartResponse(BaseModel):
    requestId: str
    customerId: int
    outcome: Literal[
        "STARTED", "INFORMATION_ONLY", "CLARIFICATION_REQUIRED", "UNSUPPORTED",
        "NO_EXECUTABLE_TASK", "COMPOSITE_NOT_SUPPORTED", "TASK_NOT_AUTOSTART_ENABLED",
        "TASK_ACTION_AVAILABLE",
    ]
    classification: IntentClassification
    workflow: WorkflowStartData | None = None
    loanInquiry: LoanInquiryData | None = None
    executionStatus: ExecutionStatus | None = None
    taskAction: CustomerTaskAction | None = None
    answer: str | None = None
    answerStatus: Literal["GENERATED", "FAILED", "NOT_APPLICABLE"] = "NOT_APPLICABLE"


def build_registry(gateway: SpringToolGateway | None = None) -> TaskRegistry:
    selected = gateway or SpringHttpToolGateway()
    return TaskRegistry([TransactionSearchTask(selected), LoanInquiryTask(selected),
                         RepaymentOrderChangeTask(), HousingSavingsCloseTask(selected),
                         DebtAdjustmentTask(selected),
                         InheritanceDepositTask(SpringC02ValidationGateway(selected))])


def build_intent_classifier(registry: TaskRegistry) -> IntentClassifier:
    # A task may be startable in Spring without being directly executable in
    # this registry. Only A-02 runs immediately; the other screens collect
    # customer input and consent before any financial side effect.
    available = {code.value for code in TaskCode}
    return IntentClassifier(OpenAiIntentClient(), available)


def create_router(registry: TaskRegistry | None = None, intent_classifier: IntentClassifier | None = None,
                  workflow_gateway: SpringToolGateway | None = None,
                  natural_start_enabled: bool | None = None,
                  internal_token: str | None = None) -> APIRouter:
    router = APIRouter(tags=["customer-agent"])
    selected_registry = registry or build_registry()
    selected_classifier = intent_classifier or build_intent_classifier(selected_registry)
    selected_workflow_gateway = workflow_gateway or SpringHttpToolGateway()
    start_enabled = natural_start_enabled if natural_start_enabled is not None else (
        os.getenv("CUSTOMER_AGENT_WORKFLOW_START_ENABLED", "false").strip().lower() == "true")
    expected_internal_token = (internal_token if internal_token is not None else
                               os.getenv("CUSTOMER_AGENT_INTERNAL_TOKEN", "")).strip()

    def require_internal_token(gateway_token: str | None) -> None:
        if not expected_internal_token:
            raise HTTPException(503, "CUSTOMER_AGENT_AUTH_NOT_CONFIGURED")
        if gateway_token is None or not hmac.compare_digest(gateway_token, expected_internal_token):
            raise HTTPException(401, "CUSTOMER_AGENT_AUTH_REQUIRED")

    @router.get("/customer-agent/tasks")
    def registered_tasks() -> list[dict[str, object]]:
        return selected_registry.definitions()

    @router.post("/customer-agent/execute")
    def execute(
            request: CustomerTaskRequest,
            gateway_token: str | None = Header(default=None, alias="X-Customer-Agent-Token")) -> dict:
        require_internal_token(gateway_token)
        try:
            return selected_registry.get(request.taskCode).run(request.model_dump())
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        except RuntimeError as exc:
            raise HTTPException(503, str(exc)) from exc

    @router.post("/customer-agent/classify")
    def classify(request: CustomerIntentRequest) -> dict:
        try:
            return selected_classifier.classify(request.message, request.recentMessages).model_dump(mode="json")
        except IntentOutputValidationError as exc:
            raise HTTPException(502, "INTENT_LLM_OUTPUT_INVALID") from exc
        except IntentProviderError as exc:
            raise HTTPException(503, str(exc)) from exc

    if start_enabled:
        @router.post("/customer-agent/messages/start")
        def classify_and_start(
                request: CustomerNaturalStartRequest,
                gateway_token: str | None = Header(default=None, alias="X-Customer-Agent-Token")) -> dict:
            require_internal_token(gateway_token)
            try:
                classification = selected_classifier.classify(request.message, request.recentMessages)
            except IntentOutputValidationError as exc:
                raise HTTPException(502, "INTENT_LLM_OUTPUT_INVALID") from exc
            except IntentProviderError as exc:
                raise HTTPException(503, "INTENT_CLASSIFICATION_UNAVAILABLE") from exc

            outcome = _non_start_outcome(classification)
            if outcome is not None:
                action = (CustomerTaskAction(
                    taskCode="REPAYMENT_ORDER_CHANGE", actionRequestId=request.requestId)
                    if outcome == "TASK_ACTION_AVAILABLE" else None)
                return CustomerNaturalStartResponse(
                    requestId=request.requestId, customerId=request.customerId, outcome=outcome,
                    classification=classification, answer=_non_start_answer(outcome, classification),
                    taskAction=action,
                    answerStatus="GENERATED").model_dump(mode="json")

            target = classification.executionTargets[0]
            try:
                raw = selected_workflow_gateway.workflow_start({
                    "requestId": request.requestId,
                    "customerId": request.customerId,
                    "chatRoomId": request.chatRoomId,
                    "taskCode": target.value,
                })
                envelope = WorkflowStartEnvelope.model_validate(raw)
                if envelope.requestId != request.requestId or envelope.taskCode is not target:
                    raise ValueError("workflow start response does not match the request")
            except ValidationError as exc:
                raise HTTPException(502, "WORKFLOW_START_INVALID_RESPONSE") from exc
            except ValueError as exc:
                raise HTTPException(502, "WORKFLOW_START_INVALID_RESPONSE") from exc
            except SpringToolHttpError as exc:
                if exc.status_code == 409 and exc.error_code == "DUPLICATE_REQUEST_CONFLICT":
                    raise HTTPException(409, "DUPLICATE_REQUEST_CONFLICT") from exc
                raise HTTPException(503, "WORKFLOW_START_UNAVAILABLE") from exc
            except RuntimeError as exc:
                raise HTTPException(503, "WORKFLOW_START_UNAVAILABLE") from exc

            if target is not TaskCode.LOAN_INQUIRY:
                return CustomerNaturalStartResponse(
                    requestId=request.requestId, customerId=request.customerId, outcome="STARTED",
                    classification=classification, workflow=WorkflowStartData(
                        workflowId=envelope.data.workflowId,
                        workItemId=envelope.data.workItemId,
                        taskCode=envelope.data.taskCode,
                        status=envelope.data.status),
                    answer="업무를 준비했습니다. 화면에서 필요한 정보를 확인한 뒤 계속해 주세요.",
                    answerStatus="GENERATED").model_dump(mode="json")

            try:
                task_result = selected_registry.get(TaskCode.LOAN_INQUIRY.value).run({
                    "taskCode": TaskCode.LOAN_INQUIRY.value,
                    "requestId": request.requestId,
                    "workItemId": envelope.data.workItemId,
                    "customerId": request.customerId,
                })
                loan_envelope = LoanInquiryEnvelope.model_validate(task_result)
                if (loan_envelope.requestId != request.requestId
                        or loan_envelope.workItemId != envelope.data.workItemId):
                    raise ValueError("loan inquiry response does not match the workflow start")
                if (loan_envelope.data.workItemStatus != "DONE"
                        or loan_envelope.data.workflowStatus != "COMPLETED"):
                    raise ValueError("loan inquiry completion status is not authoritative")
            except (ValidationError, ValueError) as exc:
                raise HTTPException(502, "LOAN_INQUIRY_INVALID_RESPONSE") from exc
            except RuntimeError as exc:
                raise HTTPException(503, "LOAN_INQUIRY_UNAVAILABLE") from exc

            try:
                answer = LoanInquiryAnswerTask().run(loan_envelope.data.loans, _loan_answer)
                answer_status = "GENERATED"
            except Exception:
                answer = None
                answer_status = "FAILED"

            return CustomerNaturalStartResponse(
                requestId=request.requestId, customerId=request.customerId, outcome="STARTED",
                classification=classification, workflow=WorkflowStartData(
                    workflowId=envelope.data.workflowId,
                    workItemId=envelope.data.workItemId,
                    taskCode=envelope.data.taskCode,
                    status=loan_envelope.data.workflowStatus),
                loanInquiry=loan_envelope.data,
                executionStatus=ExecutionStatus(
                    workflowId=envelope.data.workflowId,
                    workItemId=envelope.data.workItemId,
                    taskCode="LOAN_INQUIRY",
                    workItemStatus=loan_envelope.data.workItemStatus,
                    workflowStatus=loan_envelope.data.workflowStatus),
                answer=answer, answerStatus=answer_status).model_dump(mode="json")

    return router


def _non_start_outcome(classification: IntentClassification) -> str | None:
    if classification.requiresClarification:
        return "CLARIFICATION_REQUIRED"
    if classification.unsupported:
        return "UNSUPPORTED"
    if len(classification.tasks) > 1:
        return "COMPOSITE_NOT_SUPPORTED"
    if not classification.tasks:
        return "NO_EXECUTABLE_TASK"
    task = classification.tasks[0]
    if task.requestKind is RequestKind.INFORMATION:
        return "INFORMATION_ONLY"
    if not classification.executionTargets:
        return "NO_EXECUTABLE_TASK"
    if len(classification.executionTargets) != 1:
        return "NO_EXECUTABLE_TASK"
    # B-01 must be explicitly started through the structured chat action.
    if classification.executionTargets[0] is TaskCode.REPAYMENT_ORDER_CHANGE:
        return "TASK_ACTION_AVAILABLE"
    # C-01 must enter through its dedicated start API, which creates the Case
    # together with intake data. Do not create an orphan WorkItem here.
    if classification.executionTargets[0] is TaskCode.DEBT_ADJUSTMENT:
        return "TASK_NOT_AUTOSTART_ENABLED"
    return None


def _non_start_answer(outcome: str, classification: IntentClassification) -> str:
    if outcome == "CLARIFICATION_REQUIRED":
        return classification.clarificationQuestion or "어떤 금융업무를 원하시는지 조금 더 알려주세요."
    if outcome == "INFORMATION_ONLY":
        return "업무 안내 질문으로 확인했습니다. 이번 요청에서는 금융업무를 실행하지 않았습니다."
    if outcome == "UNSUPPORTED":
        return classification.unsupportedReason or "현재 지원하지 않는 금융업무입니다."
    if outcome == "COMPOSITE_NOT_SUPPORTED":
        return "여러 업무가 함께 요청되었습니다. 한 번에 한 업무씩 요청해 주세요."
    if outcome == "TASK_NOT_AUTOSTART_ENABLED":
        return "요청한 업무는 현재 채팅에서 자동으로 시작할 수 없습니다."
    if outcome == "TASK_ACTION_AVAILABLE":
        return ("채무변제충당순서 변경 신청을 도와드리겠습니다. "
                "신청 화면에서 대출계좌를 선택하고 필요한 서류를 제출할 수 있습니다.")
    return "실행할 수 있는 금융업무를 확인하지 못했습니다. 요청을 구체적으로 알려주세요."


def _loan_answer(loans: list[LoanSummary]) -> str:
    """Render only validated Tool fields; no model-generated financial values are accepted."""
    if not loans:
        return "조회된 대출계좌가 없습니다."
    lines = [f"대출계좌 {len(loans)}건을 조회했습니다."]
    for index, loan in enumerate(loans, 1):
        overdue = f", 연체 {loan.overdueDays}일" if loan.overdueDays else ", 연체 없음"
        lines.append(
            f"{index}. {loan.loanName} ({loan.loanNoMasked}): "
            f"잔액 {loan.balance:,.0f}원, 금리 {loan.interestRate}%{overdue}")
    return "\n".join(lines)
