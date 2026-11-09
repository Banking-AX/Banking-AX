from __future__ import annotations

import os
import hmac
import re
from contextlib import asynccontextmanager
from decimal import Decimal
from typing import Callable, Literal

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator
from .contracts import SpringToolGateway
from .b01_checkpoint import B01CheckpointStore
from .registry import TaskRegistry
from .tasks.loan_inquiry import LoanInquiryAnswerTask, LoanInquiryTask
from .tasks.transaction_search import TransactionSearchTask
from .tasks.housing_savings_close import HousingSavingsCloseTask
from .tasks.debt_adjustment import DebtAdjustmentTask
from .tasks.repayment_order_change import (
    B01GraphRunResult,
    B01GraphState,
    B01GraphWorkflow,
    RepaymentOrderChangeTask,
    b01_thread_id,
    initialize_b01_state,
)
from app.tasks.c02 import InheritanceDepositTask, SpringC02ValidationGateway
from .tool_gateway import SpringHttpToolGateway, SpringToolHttpError
from .intent_classifier import (
    ClassifiedTask,
    IntentClassifier,
    IntentClassification,
    IntentOutputValidationError,
    IntentProviderError,
    OpenAiIntentClient,
    RequestKind,
    TaskCode,
)


InformationGuide = Callable[[str, str], dict[str, object]]


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
    message: str = Field(min_length=1, max_length=2000)
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
    status: Literal["ACTIVE", "ON_HOLD", "DONE", "COMPLETED"]


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


class B01LoanSelectionCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    number: int = Field(gt=0)
    loanId: int = Field(gt=0)
    loanName: str = Field(min_length=1, max_length=200)
    loanNoMasked: str = Field(min_length=3, max_length=100)


class B01LoanSelectionAction(BaseModel):
    type: Literal["SELECT_B01_LOAN"] = "SELECT_B01_LOAN"
    candidates: list[B01LoanSelectionCandidate] = Field(min_length=1)


class B01DocumentAction(BaseModel):
    type: Literal["DOWNLOAD_B01_APPLICATION"] = "DOWNLOAD_B01_APPLICATION"
    fileName: Literal["b01-repayment-order-change-application-201903.pdf"] = (
        "b01-repayment-order-change-application-201903.pdf")
    downloadPath: Literal["/api/v1/customer/b01/application-form"] = (
        "/api/v1/customer/b01/application-form")


class B01ReconnectCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workItemId: int = Field(gt=0)
    status: Literal["ACTIVE", "ON_HOLD"]
    originalChatRoomId: int | None = Field(default=None, gt=0)
    originalChatRoomAvailable: bool

    @model_validator(mode="after")
    def validate_original_chat(self) -> "B01ReconnectCandidate":
        if self.originalChatRoomAvailable and self.originalChatRoomId is None:
            raise ValueError("An available original chat room must have an id")
        return self


class B01ReconnectAction(BaseModel):
    type: Literal["SELECT_B01_RECONNECT"] = "SELECT_B01_RECONNECT"
    allowStartNew: bool
    candidates: list[B01ReconnectCandidate] = Field(min_length=1)


class B01PrepareData(BaseModel):
    model_config = ConfigDict(extra="forbid")

    outcome: Literal[
        "CREATED", "REQUEST_REPLAYED", "SAME_CHAT_REUSED",
        "RECONNECT_CONFIRMATION_REQUIRED", "EXISTING_RECONNECTED",
        "ACTIVE_WORK_ITEM_CONFLICT",
    ]
    workItem: WorkflowStartData
    candidates: list[B01ReconnectCandidate] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_candidates(self) -> "B01PrepareData":
        if self.outcome == "RECONNECT_CONFIRMATION_REQUIRED":
            if not self.candidates:
                raise ValueError("B-01 reconnect candidates are required")
            ids = [candidate.workItemId for candidate in self.candidates]
            if len(ids) != len(set(ids)):
                raise ValueError("B-01 reconnect candidate ids must be unique")
        elif self.candidates:
            raise ValueError("B-01 reconnect candidates are only valid for reconnect confirmation")
        return self


class B01PrepareEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requestId: str
    taskCode: Literal["REPAYMENT_ORDER_CHANGE"]
    workItemId: int = Field(gt=0)
    caseId: None
    revision: None
    data: B01PrepareData
    error: None

    @model_validator(mode="after")
    def validate_consistency(self) -> "B01PrepareEnvelope":
        if self.workItemId != self.data.workItem.workItemId:
            raise ValueError("B-01 prepare envelope WorkItem fields do not match")
        if self.data.workItem.taskCode is not TaskCode.REPAYMENT_ORDER_CHANGE:
            raise ValueError("B-01 prepare returned a different task")
        return self


class B01GraphEntry(BaseModel):
    threadId: str = Field(pattern=r"^b01:[1-9][0-9]*$")
    prepareOutcome: Literal["CREATED", "REQUEST_REPLAYED", "SAME_CHAT_REUSED", "EXISTING_RECONNECTED"] | None = None
    state: dict[str, object]
    interrupted: bool = False
    awaitingInput: Literal[
        "LOAN_SELECTION", "LOAN_CONFIRMATION", "REPAYMENT_ORDER_SELECTION",
    ] | None = None


class CustomerNaturalStartResponse(BaseModel):
    requestId: str
    customerId: int
    outcome: Literal[
        "STARTED", "INFORMATION_ONLY", "CLARIFICATION_REQUIRED", "UNSUPPORTED",
        "NO_EXECUTABLE_TASK", "COMPOSITE_NOT_SUPPORTED", "TASK_NOT_AUTOSTART_ENABLED",
        "TASK_ACTION_AVAILABLE", "B01_RECONNECT_CONFIRMATION_REQUIRED",
        "B01_ACTIVE_WORK_ITEM_CONFLICT",
    ]
    classification: IntentClassification
    workflow: WorkflowStartData | None = None
    loanInquiry: LoanInquiryData | None = None
    executionStatus: ExecutionStatus | None = None
    taskAction: CustomerTaskAction | None = None
    b01LoanSelectionAction: B01LoanSelectionAction | None = None
    b01DocumentAction: B01DocumentAction | None = None
    b01ReconnectAction: B01ReconnectAction | None = None
    b01Entry: B01GraphEntry | None = None
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
                  b01_graph_entry_enabled: bool | None = None,
                  b01_checkpoint_store: B01CheckpointStore | None = None,
                  internal_token: str | None = None,
                  information_guide: InformationGuide | None = None) -> APIRouter:
    start_enabled = natural_start_enabled if natural_start_enabled is not None else (
        os.getenv("CUSTOMER_AGENT_WORKFLOW_START_ENABLED", "false").strip().lower() == "true")
    b01_entry_enabled = b01_graph_entry_enabled if b01_graph_entry_enabled is not None else (
        os.getenv("CUSTOMER_AGENT_B01_GRAPH_ENTRY_ENABLED", "false").strip().lower() == "true")
    selected_checkpoint_store = b01_checkpoint_store or B01CheckpointStore()
    owns_checkpoint_store = b01_checkpoint_store is None

    @asynccontextmanager
    async def lifespan(_: object):
        if b01_entry_enabled:
            selected_checkpoint_store.open()
        try:
            yield
        finally:
            if owns_checkpoint_store:
                selected_checkpoint_store.close()

    router = APIRouter(tags=["customer-agent"], lifespan=lifespan)
    selected_registry = registry or build_registry()
    selected_classifier = intent_classifier or build_intent_classifier(selected_registry)
    selected_workflow_gateway = workflow_gateway or SpringHttpToolGateway()
    selected_information_guide = information_guide or _build_information_guide
    def require_internal_token(gateway_token: str | None) -> None:
        expected_internal_token = (internal_token if internal_token is not None else
                                   os.getenv("CUSTOMER_AGENT_INTERNAL_TOKEN", "")).strip()
        if not expected_internal_token:
            raise HTTPException(503, "CUSTOMER_AGENT_AUTH_NOT_CONFIGURED")
        if gateway_token is None or not hmac.compare_digest(gateway_token, expected_internal_token):
            raise HTTPException(401, "CUSTOMER_AGENT_AUTH_REQUIRED")

    @router.get("/customer-agent/tasks")
    def registered_tasks(gateway_token: str | None = Header(default=None, alias="X-Customer-Agent-Token")) -> list[dict[str, object]]:
        require_internal_token(gateway_token)
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
    def classify(request: CustomerIntentRequest,
                 gateway_token: str | None = Header(default=None, alias="X-Customer-Agent-Token")) -> dict:
        require_internal_token(gateway_token)
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
            if b01_entry_enabled:
                selected_checkpoint_store.open()
                replay = selected_checkpoint_store.find_request(
                    request.customerId, request.chatRoomId, request.requestId)
                if replay is not None:
                    workflow = B01GraphWorkflow(
                        selected_workflow_gateway, request.customerId, request.requestId,
                        selected_checkpoint_store.saver)
                    with selected_checkpoint_store.execution_lock(replay.work_item_id):
                        result = workflow.current(replay.work_item_id)
                    return _b01_graph_response(
                        request, _b01_followup_classification(), result)

                pending = selected_checkpoint_store.find_pending(
                    request.customerId, request.chatRoomId)
                if pending is not None and not _clearly_requests_another_task(request.message):
                    if _looks_like_information_question(request.message):
                        try:
                            pending_classification = selected_classifier.classify(
                                request.message, request.recentMessages)
                        except IntentOutputValidationError as exc:
                            raise HTTPException(502, "INTENT_LLM_OUTPUT_INVALID") from exc
                        except IntentProviderError as exc:
                            raise HTTPException(503, "INTENT_CLASSIFICATION_UNAVAILABLE") from exc
                        if _is_b01_information(pending_classification):
                            workflow = B01GraphWorkflow(
                                selected_workflow_gateway, request.customerId, request.requestId,
                                selected_checkpoint_store.saver)
                            with selected_checkpoint_store.execution_lock(pending.work_item_id):
                                current = workflow.current(pending.work_item_id)
                            return _b01_pending_information_response(
                                request, pending_classification, current,
                                selected_information_guide)
                    workflow = B01GraphWorkflow(
                        selected_workflow_gateway, request.customerId, request.requestId,
                        selected_checkpoint_store.saver)
                    try:
                        with selected_checkpoint_store.execution_lock(pending.work_item_id):
                            result = workflow.resume(pending.work_item_id, request.message)
                    except (RuntimeError, ValueError) as exc:
                        raise HTTPException(409, "B01_RESUME_CONFLICT") from exc
                    return _b01_graph_response(
                        request, _b01_followup_classification(), result)

            try:
                classification = selected_classifier.classify(request.message, request.recentMessages)
            except IntentOutputValidationError as exc:
                raise HTTPException(502, "INTENT_LLM_OUTPUT_INVALID") from exc
            except IntentProviderError as exc:
                raise HTTPException(503, "INTENT_CLASSIFICATION_UNAVAILABLE") from exc

            outcome = _non_start_outcome(classification, b01_entry_enabled)
            if outcome is not None:
                action = (CustomerTaskAction(
                    taskCode="REPAYMENT_ORDER_CHANGE", actionRequestId=request.requestId)
                    if outcome == "TASK_ACTION_AVAILABLE" else None)
                if outcome == "INFORMATION_ONLY":
                    answer, answer_status = _information_answer(
                        request.message, classification, selected_information_guide)
                else:
                    answer = _non_start_answer(outcome, classification)
                    answer_status = "GENERATED"
                return CustomerNaturalStartResponse(
                    requestId=request.requestId, customerId=request.customerId, outcome=outcome,
                    classification=classification, answer=answer,
                    taskAction=action,
                    answerStatus=answer_status).model_dump(mode="json")

            target = classification.executionTargets[0]
            if target is TaskCode.REPAYMENT_ORDER_CHANGE:
                return _prepare_b01_entry(
                    request, classification, selected_workflow_gateway,
                    selected_checkpoint_store)
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


def _non_start_outcome(classification: IntentClassification,
                       b01_graph_entry_enabled: bool = False) -> str | None:
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
    if (classification.executionTargets[0] is TaskCode.REPAYMENT_ORDER_CHANGE
            and not b01_graph_entry_enabled):
        return "TASK_ACTION_AVAILABLE"
    # C-01 must enter through its dedicated start API, which creates the Case
    # together with intake data. Do not create an orphan WorkItem here.
    if classification.executionTargets[0] is TaskCode.DEBT_ADJUSTMENT:
        return "TASK_NOT_AUTOSTART_ENABLED"
    return None


def _prepare_b01_entry(request: CustomerNaturalStartRequest,
                       classification: IntentClassification,
                       gateway: SpringToolGateway,
                       checkpoint_store: B01CheckpointStore) -> dict:
    try:
        raw = gateway.b01_prepare({
            "requestId": request.requestId,
            "customerId": request.customerId,
            "chatRoomId": request.chatRoomId,
            "mode": "DISCOVER",
            "includeCandidates": True,
        })
        envelope = B01PrepareEnvelope.model_validate(raw)
        if envelope.requestId != request.requestId:
            raise ValueError("B-01 prepare response does not match the request")
    except (ValidationError, ValueError) as exc:
        raise HTTPException(502, "B01_PREPARE_INVALID_RESPONSE") from exc
    except SpringToolHttpError as exc:
        if exc.status_code == 409 and exc.error_code == "DUPLICATE_REQUEST_CONFLICT":
            raise HTTPException(409, "DUPLICATE_REQUEST_CONFLICT") from exc
        raise HTTPException(503, "B01_PREPARE_UNAVAILABLE") from exc
    except RuntimeError as exc:
        raise HTTPException(503, "B01_PREPARE_UNAVAILABLE") from exc

    prepare_outcome = envelope.data.outcome
    if prepare_outcome == "RECONNECT_CONFIRMATION_REQUIRED":
        candidates = envelope.data.candidates
        return CustomerNaturalStartResponse(
            requestId=request.requestId,
            customerId=request.customerId,
            outcome="B01_RECONNECT_CONFIRMATION_REQUIRED",
            classification=classification,
            answer=("진행 중인 채무변제충당순서 변경 업무가 있습니다. "
                    "이어할 업무를 선택하거나 새 신청 가능 여부를 확인해 주세요."),
            b01ReconnectAction=B01ReconnectAction(
                allowStartNew=all(candidate.status == "ON_HOLD" for candidate in candidates),
                candidates=candidates,
            ),
            answerStatus="GENERATED",
        ).model_dump(mode="json")
    if prepare_outcome == "ACTIVE_WORK_ITEM_CONFLICT":
        return CustomerNaturalStartResponse(
            requestId=request.requestId,
            customerId=request.customerId,
            outcome="B01_ACTIVE_WORK_ITEM_CONFLICT",
            classification=classification,
            answer=("다른 채팅방에서 진행 중인 업무가 있어 새 신청을 시작할 수 없습니다. "
                    "기존 업무를 확인해 주세요."),
            answerStatus="GENERATED",
        ).model_dump(mode="json")

    work_item = envelope.data.workItem
    if work_item.status != "ACTIVE":
        return CustomerNaturalStartResponse(
            requestId=request.requestId,
            customerId=request.customerId,
            outcome="TASK_ACTION_AVAILABLE",
            classification=classification,
            workflow=work_item,
            taskAction=CustomerTaskAction(
                taskCode="REPAYMENT_ORDER_CHANGE", actionRequestId=request.requestId),
            answer="중단된 신청이 있습니다. 신청 화면에서 기존 업무를 확인하고 이어서 진행해 주세요.",
            answerStatus="GENERATED",
        ).model_dump(mode="json")

    state: B01GraphState = initialize_b01_state(
        work_item.workItemId, request.chatRoomId, request.message,
        customer_id=request.customerId, action_request_id=request.requestId,
        prepare_outcome=prepare_outcome)
    workflow = B01GraphWorkflow(
        gateway, request.customerId, request.requestId, checkpoint_store.saver)
    with checkpoint_store.execution_lock(work_item.workItemId):
        result = workflow.run(state)
    return _b01_graph_response(
        request, classification, result, workflow=work_item,
        prepare_outcome=prepare_outcome)


def _b01_graph_response(request: CustomerNaturalStartRequest,
                        classification: IntentClassification,
                        result: B01GraphRunResult,
                        workflow: WorkflowStartData | None = None,
                        prepare_outcome: str | None = None) -> dict:
    state = result["state"]
    if result["interrupted"]:
        answer = state.get("pending_question")
        if not isinstance(answer, str) or not answer.strip():
            raise RuntimeError("B01_INTERRUPT_QUESTION_MISSING")
    else:
        answer = state.get("response")
    action = None
    if result["taskAction"] is not None:
        action_request_id = state.get("action_request_id") or request.requestId
        action = CustomerTaskAction(
            taskCode="REPAYMENT_ORDER_CHANGE", actionRequestId=action_request_id)
    loan_selection_action = None
    if (result["interrupted"]
            and state.get("awaiting_input") == "LOAN_SELECTION"):
        candidates = state.get("loan_candidates") or []
        if candidates:
            loan_selection_action = B01LoanSelectionAction(candidates=[
                B01LoanSelectionCandidate(
                    number=index,
                    loanId=candidate["loanId"],
                    loanName=candidate["loanName"],
                    loanNoMasked=candidate["loanNoMasked"],
                )
                for index, candidate in enumerate(candidates, 1)
            ])
    document_action = B01DocumentAction() if action is not None else None
    outcome = "TASK_ACTION_AVAILABLE" if action is not None else "STARTED"
    return CustomerNaturalStartResponse(
        requestId=request.requestId,
        customerId=request.customerId,
        outcome=outcome,
        classification=classification,
        workflow=workflow,
        taskAction=action,
        b01LoanSelectionAction=loan_selection_action,
        b01DocumentAction=document_action,
        b01Entry=B01GraphEntry(
            threadId=b01_thread_id(state["work_item_id"]),
            prepareOutcome=prepare_outcome or state.get("prepare_outcome"),
            state=state,
            interrupted=result["interrupted"],
            awaitingInput=state.get("awaiting_input"),
        ),
        answer=answer,
        answerStatus="FAILED" if state.get("tool_error") else "GENERATED",
    ).model_dump(mode="json")


def _b01_followup_classification() -> IntentClassification:
    task = ClassifiedTask(
        order=1,
        taskCode=TaskCode.REPAYMENT_ORDER_CHANGE,
        requestKind=RequestKind.ACTION,
        executionAvailable=True,
    )
    return IntentClassification(
        tasks=[task],
        executionTargets=[TaskCode.REPAYMENT_ORDER_CHANGE],
        requiresClarification=False,
        clarificationQuestion=None,
        unsupported=False,
        unsupportedReason=None,
    )


def _is_b01_information(classification: IntentClassification) -> bool:
    return (len(classification.tasks) == 1
            and classification.tasks[0].taskCode is TaskCode.REPAYMENT_ORDER_CHANGE
            and classification.tasks[0].requestKind is RequestKind.INFORMATION)


def _looks_like_information_question(message: str) -> bool:
    compact = re.sub(r"\s+", "", message)
    return any(token in compact for token in (
        "뭐야", "무슨뜻", "어떤뜻", "어떤의미", "설명해", "궁금해",
    ))


def _b01_pending_information_response(
        request: CustomerNaturalStartRequest,
        classification: IntentClassification,
        current: B01GraphRunResult,
        guide_builder: InformationGuide) -> dict:
    state = current["state"]
    answer, answer_status = _information_answer(
        request.message, classification, guide_builder)
    return CustomerNaturalStartResponse(
        requestId=request.requestId,
        customerId=request.customerId,
        outcome="INFORMATION_ONLY",
        classification=classification,
        b01Entry=B01GraphEntry(
            threadId=b01_thread_id(state["work_item_id"]),
            prepareOutcome=state.get("prepare_outcome"),
            state=state,
            interrupted=current["interrupted"],
            awaitingInput=state.get("awaiting_input"),
        ),
        answer=answer,
        answerStatus=answer_status,
    ).model_dump(mode="json")


def _clearly_requests_another_task(message: str) -> bool:
    compact = re.sub(r"\s+", "", message)
    return any(token in compact for token in (
        "거래내역", "대출조회", "대출정보", "대출잔액",
        "청약통장", "채무조정", "상속예금",
    ))


def _non_start_answer(outcome: str, classification: IntentClassification) -> str:
    if outcome == "CLARIFICATION_REQUIRED":
        return classification.clarificationQuestion or "어떤 금융업무를 원하시는지 조금 더 알려주세요."
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


def _build_information_guide(query: str, task_code: str) -> dict[str, object]:
    """Reuse the verified customer RAG guide without making an authenticated HTTP self-call."""
    from app.retrieve import search
    from app.task_guide import build_guide, default_llm

    return build_guide(query, task_code, search=search, llm=default_llm())


def _information_answer(
        query: str,
        classification: IntentClassification,
        guide_builder: InformationGuide) -> tuple[str, Literal["GENERATED", "FAILED"]]:
    task_code = classification.tasks[0].taskCode.value
    try:
        guide = guide_builder(query, task_code)
        bullets = guide.get("bullets")
        if not isinstance(bullets, list) or not bullets:
            raise ValueError("verified RAG evidence is unavailable")
        texts = [item.get("text") for item in bullets if isinstance(item, dict)]
        if not texts or any(not isinstance(text, str) or not text.strip() for text in texts):
            raise ValueError("verified RAG guide is invalid")
        official_name = guide.get("officialName")
        if not isinstance(official_name, str) or not official_name.strip():
            raise ValueError("verified RAG task name is missing")
        answer = "\n".join([f"{official_name} 안내입니다.", *(f"- {text}" for text in texts)])
        return answer, "GENERATED"
    except Exception:  # noqa: BLE001 - retrieval/provider failures must not produce invented guidance
        return "검수된 금융업무 안내 근거를 찾지 못했습니다. 잠시 후 다시 확인해 주세요.", "FAILED"


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
