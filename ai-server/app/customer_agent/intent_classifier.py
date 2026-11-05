from __future__ import annotations

import os
from enum import Enum
from typing import Any, Callable, Protocol, TypedDict

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator


class TaskCode(str, Enum):
    TRANSACTION_SEARCH = "TRANSACTION_SEARCH"
    LOAN_INQUIRY = "LOAN_INQUIRY"
    REPAYMENT_ORDER_CHANGE = "REPAYMENT_ORDER_CHANGE"
    HOUSING_SAVINGS_CLOSE = "HOUSING_SAVINGS_CLOSE"
    DEBT_ADJUSTMENT = "DEBT_ADJUSTMENT"
    INHERITANCE_DEPOSIT = "INHERITANCE_DEPOSIT"


class RequestKind(str, Enum):
    INFORMATION = "INFORMATION"
    ACTION = "ACTION"


class LlmIntentItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    order: int = Field(ge=1, le=6)
    taskCode: TaskCode
    requestKind: RequestKind


class LlmIntentOutput(BaseModel):
    """Strict output accepted from the LLM. It contains no execution authority."""

    model_config = ConfigDict(extra="forbid")

    tasks: list[LlmIntentItem] = Field(max_length=6)
    requiresClarification: bool
    clarificationQuestion: str | None = Field(min_length=1, max_length=300)
    unsupported: bool
    unsupportedReason: str | None = Field(min_length=1, max_length=300)

    @model_validator(mode="after")
    def validate_consistency(self) -> "LlmIntentOutput":
        codes = [item.taskCode for item in self.tasks]
        if len(codes) != len(set(codes)):
            raise ValueError("duplicate taskCode is not allowed")
        if [item.order for item in self.tasks] != list(range(1, len(self.tasks) + 1)):
            raise ValueError("task order must be contiguous and preserve the requested sequence")
        if self.requiresClarification and self.unsupported:
            raise ValueError("a request cannot be ambiguous and unsupported at the same time")
        if self.requiresClarification:
            if self.tasks or not self.clarificationQuestion:
                raise ValueError("ambiguous requests must contain only a clarification question")
        elif self.clarificationQuestion is not None:
            raise ValueError("clarificationQuestion requires requiresClarification=true")
        if self.unsupported:
            if self.tasks or not self.unsupportedReason:
                raise ValueError("unsupported requests must contain only an unsupported reason")
        elif self.unsupportedReason is not None:
            raise ValueError("unsupportedReason requires unsupported=true")
        if not self.tasks and not self.requiresClarification and not self.unsupported:
            raise ValueError("classification must contain tasks, clarification, or unsupported result")
        return self


class ClassifiedTask(BaseModel):
    order: int
    taskCode: TaskCode
    requestKind: RequestKind
    executionAvailable: bool


class IntentClassification(BaseModel):
    tasks: list[ClassifiedTask]
    executionTargets: list[TaskCode]
    requiresClarification: bool
    clarificationQuestion: str | None
    unsupported: bool
    unsupportedReason: str | None


class IntentClassificationState(TypedDict, total=False):
    message: str
    recentMessages: list[str]
    classification: dict[str, Any]


class IntentProviderError(RuntimeError):
    pass


class IntentOutputValidationError(RuntimeError):
    pass


class IntentLlmClient(Protocol):
    def classify(self, message: str, recent_messages: list[str]) -> str: ...


SYSTEM_PROMPT = """
당신은 Banking-AX 고객 요청의 업무 의도 분류기다. 금융정보를 조회하거나 변경하지 말고 의도만 JSON으로 분류한다.

선택 가능한 업무 코드는 아래 6개뿐이다.
- TRANSACTION_SEARCH: 입출금·거래내역, 거래 기간/금액/상대방 조회.
- LOAN_INQUIRY: 대출계좌 목록, 대출 잔액·금리·연체 여부 등 현재 대출정보 조회. 대출 조건을 변경하지 않는다.
- REPAYMENT_ORDER_CHANGE: 대출 상환금의 충당순서를 이자 우선/원금 우선 등으로 변경하려는 요청.
- HOUSING_SAVINGS_CLOSE: 청년 주택드림 청약통장을 해지하려는 요청. 일반 예적금 가입·해지는 해당하지 않는다.
- DEBT_ADJUSTMENT: 상환 곤란 고객의 채무조정 사전접수 요청. 단순 대출 조회나 충당순서 변경과 구분한다.
- INHERITANCE_DEPOSIT: 사망자의 예금 상속 해지·지급을 위한 사전접수 요청.

분류 규칙:
1. 해당 금융업무의 개념·절차·필요서류를 묻는 질문은 requestKind=INFORMATION이다. INFORMATION도 대응하는 업무 코드를 tasks에 포함하지만 실제 업무 실행 대상은 아니다.
2. 실제 금융정보 조회, 신청, 변경, 해지를 요청하는 문장은 requestKind=ACTION이다.
3. 여러 업무가 명시되면 고객이 말한 순서대로 order를 1부터 연속 부여한다.
4. 부정된 업무는 제외한다. "아니", "말고", "취소" 같은 정정에서는 최신의 명시적 요청만 반영한다.
5. "대출 관련 업무"처럼 어느 업무인지 확정할 수 없으면 tasks를 비우고 requiresClarification=true로 설정한다.
6. 일반 금융 질문이 6개 업무 중 하나에 명확히 대응하지 않으면 억지로 코드를 지정하지 말고 unsupported=true로 설정한다.
7. 지원하지 않는 업무면 tasks를 비우고 unsupported=true로 설정한다.
8. confidence 숫자를 만들지 말고, 추측으로 업무를 확정하지 않는다.
9. 지정된 JSON Schema 이외의 텍스트나 필드를 출력하지 않는다.

예시:
- "채무조정이 뭔지 설명해 줘" -> DEBT_ADJUSTMENT, INFORMATION
- "채무조정 신청하려면 어떤 서류가 필요해?" -> DEBT_ADJUSTMENT, INFORMATION
- "채무조정 신청해 줘" -> DEBT_ADJUSTMENT, ACTION
- "대출 상품에 대해 일반적으로 설명해 줘" -> 특정 지원 업무가 아니므로 unsupported=true
- "대출 관련 업무 좀 처리하고 싶어" -> 업무를 확정하지 말고 requiresClarification=true와 clarificationQuestion을 설정
- "대출금 갚는 순서를 바꾸고 싶어" -> REPAYMENT_ORDER_CHANGE, ACTION
- "이자보다 원금부터 갚는 방식으로 바꿀 수 있어?" -> REPAYMENT_ORDER_CHANGE, ACTION
- "채무조정 신청" -> DEBT_ADJUSTMENT, ACTION. REPAYMENT_ORDER_CHANGE로 분류하지 않는다.
- "채무조정변경신청"처럼 두 업무의 의미가 섞여 확정할 수 없으면 requiresClarification=true로 설정한다.
""".strip()


class OpenAiIntentClient:
    """Uses the same OpenAI environment variables and call style as /api/v1/rag/answer."""

    def classify(self, message: str, recent_messages: list[str]) -> str:
        api_key = os.getenv("OPENAI_API_KEY")
        if not api_key:
            raise IntentProviderError("INTENT_LLM_NOT_CONFIGURED")
        from openai import OpenAI

        base_url = os.getenv("OPENAI_BASE_URL", "").strip()
        options: dict[str, Any] = {
            "api_key": api_key,
            # OpenAI SDK 3.x also reads OPENAI_BASE_URL directly. Supplying the
            # default explicitly prevents an empty/whitespace env value from
            # becoming an invalid request URL.
            "base_url": base_url or "https://api.openai.com/v1",
        }
        client = OpenAI(**options, timeout=18, max_retries=1)
        context = "\n".join(f"- {item}" for item in recent_messages[-5:]) or "(없음)"
        try:
            completion = client.chat.completions.create(
                model=os.getenv("OPENAI_MODEL", "gpt-4.1-mini"),
                messages=[
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": f"최근 대화:\n{context}\n\n현재 고객 메시지:\n{message}"},
                ],
                temperature=0,
                response_format={
                    "type": "json_schema",
                    "json_schema": {
                        "name": "banking_ax_customer_intent",
                        "strict": True,
                        "schema": LlmIntentOutput.model_json_schema(),
                    },
                },
            )
        except Exception as exc:
            raise IntentProviderError("INTENT_LLM_REQUEST_FAILED") from exc
        content = completion.choices[0].message.content
        if not content:
            raise IntentOutputValidationError("INTENT_LLM_EMPTY_OUTPUT")
        return content


class IntentClassifier:
    def __init__(self, client: IntentLlmClient, execution_available_codes: set[str]) -> None:
        self.client = client
        self.execution_available_codes = frozenset(execution_available_codes)

    def classify(self, message: str, recent_messages: list[str] | None = None) -> IntentClassification:
        raw = self.client.classify(message, recent_messages or [])
        try:
            llm_output = LlmIntentOutput.model_validate_json(raw)
        except (ValidationError, ValueError, TypeError) as exc:
            raise IntentOutputValidationError("INTENT_LLM_OUTPUT_INVALID") from exc

        tasks = [ClassifiedTask(
            order=item.order,
            taskCode=item.taskCode,
            requestKind=item.requestKind,
            executionAvailable=item.taskCode.value in self.execution_available_codes,
        ) for item in llm_output.tasks]
        execution_targets = [item.taskCode for item in tasks
                             if item.requestKind is RequestKind.ACTION and item.executionAvailable]
        return IntentClassification(
            tasks=tasks,
            executionTargets=execution_targets,
            requiresClarification=llm_output.requiresClarification,
            clarificationQuestion=llm_output.clarificationQuestion,
            unsupported=llm_output.unsupported,
            unsupportedReason=llm_output.unsupportedReason,
        )

    def as_langgraph_node(self) -> Callable[[IntentClassificationState], dict[str, Any]]:
        def classify_node(state: IntentClassificationState) -> dict[str, Any]:
            result = self.classify(state["message"], state.get("recentMessages", []))
            return {"classification": result.model_dump(mode="json")}
        return classify_node
