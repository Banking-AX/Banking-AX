from __future__ import annotations

import os
from decimal import Decimal
from typing import Literal

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator
from .contracts import SpringToolGateway
from .registry import TaskRegistry
from .tasks.loan_inquiry import LoanInquiryTask
from .tasks.transaction_search import TransactionSearchTask
from .tasks.housing_savings_close import HousingSavingsCloseTask
from .tasks.debt_adjustment import DebtAdjustmentTask
from app.tasks.c02 import InheritanceDepositTask, SpringC02ValidationGateway
from .tool_gateway import SpringHttpToolGateway
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


class WorkflowStartData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workflowId: int = Field(gt=0)
    workItemId: int = Field(gt=0)
    taskCode: TaskCode
    status: Literal["ACTIVE", "DONE"]


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


class LoanInquiryEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requestId: str
    taskCode: Literal["LOAN_INQUIRY"]
    workItemId: int = Field(gt=0)
    caseId: None
    revision: None
    data: LoanInquiryData
    error: None


class CustomerNaturalStartResponse(BaseModel):
    requestId: str
    customerId: int
    outcome: Literal[
        "STARTED", "INFORMATION_ONLY", "CLARIFICATION_REQUIRED", "UNSUPPORTED",
        "NO_EXECUTABLE_TASK", "COMPOSITE_NOT_SUPPORTED", "TASK_NOT_AUTOSTART_ENABLED",
    ]
    classification: IntentClassification
    workflow: WorkflowStartData | None = None
    loanInquiry: LoanInquiryData | None = None


def build_registry(gateway: SpringToolGateway | None = None) -> TaskRegistry:
    selected = gateway or SpringHttpToolGateway()
    return TaskRegistry([TransactionSearchTask(selected), HousingSavingsCloseTask(selected), LoanInquiryTask(selected), DebtAdjustmentTask(selected), InheritanceDepositTask(SpringC02ValidationGateway(selected))])


def build_intent_classifier(registry: TaskRegistry) -> IntentClassifier:
    available = {str(definition["taskCode"]) for definition in registry.definitions()}
    return IntentClassifier(OpenAiIntentClient(), available)


def create_router(registry: TaskRegistry | None = None, intent_classifier: IntentClassifier | None = None,
                  workflow_gateway: SpringToolGateway | None = None,
                  natural_start_enabled: bool | None = None) -> APIRouter:
    router = APIRouter(tags=["customer-agent"])
    selected_registry = registry or build_registry()
    selected_classifier = intent_classifier or build_intent_classifier(selected_registry)
    selected_workflow_gateway = workflow_gateway or SpringHttpToolGateway()
    start_enabled = natural_start_enabled if natural_start_enabled is not None else (
        os.getenv("CUSTOMER_AGENT_WORKFLOW_START_ENABLED", "false").strip().lower() == "true")

    @router.get("/customer-agent/tasks")
    def registered_tasks() -> list[dict[str, object]]:
        return selected_registry.definitions()

    @router.post("/customer-agent/execute")
    def execute(request: CustomerTaskRequest) -> dict:
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
        def classify_and_start(request: CustomerNaturalStartRequest) -> dict:
            try:
                classification = selected_classifier.classify(request.message, request.recentMessages)
            except IntentOutputValidationError as exc:
                raise HTTPException(502, "INTENT_LLM_OUTPUT_INVALID") from exc
            except IntentProviderError as exc:
                raise HTTPException(503, "INTENT_CLASSIFICATION_UNAVAILABLE") from exc

            outcome = _non_start_outcome(classification)
            if outcome is not None:
                return CustomerNaturalStartResponse(
                    requestId=request.requestId, customerId=request.customerId, outcome=outcome,
                    classification=classification).model_dump(mode="json")

            try:
                raw = selected_workflow_gateway.workflow_start({
                    "requestId": request.requestId,
                    "customerId": request.customerId,
                    "taskCode": TaskCode.LOAN_INQUIRY.value,
                })
                envelope = WorkflowStartEnvelope.model_validate(raw)
                if envelope.requestId != request.requestId or envelope.taskCode is not TaskCode.LOAN_INQUIRY:
                    raise ValueError("workflow start response does not match the request")
            except ValidationError as exc:
                raise HTTPException(502, "WORKFLOW_START_INVALID_RESPONSE") from exc
            except ValueError as exc:
                raise HTTPException(502, "WORKFLOW_START_INVALID_RESPONSE") from exc
            except RuntimeError as exc:
                raise HTTPException(503, "WORKFLOW_START_UNAVAILABLE") from exc

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
            except (ValidationError, ValueError) as exc:
                raise HTTPException(502, "LOAN_INQUIRY_INVALID_RESPONSE") from exc
            except RuntimeError as exc:
                raise HTTPException(503, "LOAN_INQUIRY_UNAVAILABLE") from exc

            return CustomerNaturalStartResponse(
                requestId=request.requestId, customerId=request.customerId, outcome="STARTED",
                classification=classification, workflow=envelope.data,
                loanInquiry=loan_envelope.data).model_dump(mode="json")

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
    if classification.executionTargets != [TaskCode.LOAN_INQUIRY]:
        return "TASK_NOT_AUTOSTART_ENABLED" if classification.executionTargets else "NO_EXECUTABLE_TASK"
    return None
