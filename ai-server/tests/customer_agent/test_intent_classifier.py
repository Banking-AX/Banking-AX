from __future__ import annotations

import json
import os
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.customer_agent.fakes import FakeSpringToolGateway
from app.customer_agent.intent_classifier import (
    IntentClassifier,
    IntentOutputValidationError,
    IntentProviderError,
    OpenAiIntentClient,
    RequestKind,
    TaskCode,
)
from app.customer_agent.registry import TaskRegistry
from app.customer_agent.router import create_router
from app.customer_agent.tasks.loan_inquiry import LoanInquiryTask


EXECUTABLE_EXCEPT_B01 = {
    "TRANSACTION_SEARCH", "LOAN_INQUIRY", "HOUSING_SAVINGS_CLOSE",
    "DEBT_ADJUSTMENT", "INHERITANCE_DEPOSIT",
}


class StubIntentClient:
    def __init__(self, outputs: dict[str, str]) -> None:
        self.outputs = outputs
        self.calls: list[tuple[str, list[str]]] = []

    def classify(self, message: str, recent_messages: list[str]) -> str:
        self.calls.append((message, recent_messages))
        return self.outputs[message]


def action(order: int, code: str) -> dict[str, object]:
    return {"order": order, "taskCode": code, "requestKind": "ACTION"}


def information(order: int, code: str) -> dict[str, object]:
    return {"order": order, "taskCode": code, "requestKind": "INFORMATION"}


def json_output(tasks: list[dict[str, object]] | None = None, *, clarification: str | None = None,
                unsupported_reason: str | None = None) -> str:
    return json.dumps({
        "tasks": tasks or [],
        "requiresClarification": clarification is not None,
        "clarificationQuestion": clarification,
        "unsupported": unsupported_reason is not None,
        "unsupportedReason": unsupported_reason,
    }, ensure_ascii=False)


CASES = {
    "내 대출계좌 정보 조회해 줘": json_output([action(1, "LOAN_INQUIRY")]),
    "대출금 얼마나 남았어?": json_output([action(1, "LOAN_INQUIRY")]),
    "내 대출 전부 보여줘": json_output([action(1, "LOAN_INQUIRY")]),
    "이자보다 원금부터 갚고 싶어": json_output([action(1, "REPAYMENT_ORDER_CHANGE")]),
    "채무변제충당순서 변경하고 싶어": json_output([action(1, "REPAYMENT_ORDER_CHANGE")]),
    "채무변제 충당순서 변경 신청할래": json_output([action(1, "REPAYMENT_ORDER_CHANGE")]),
    "대출금 갚는 순서를 바꾸고 싶어": json_output([action(1, "REPAYMENT_ORDER_CHANGE")]),
    "이자보다 원금부터 갚는 방식으로 바꿀 수 있어?": json_output([action(1, "REPAYMENT_ORDER_CHANGE")]),
    "채무조정변경신청": json_output(clarification="채무조정 신청과 충당순서 변경 중 어떤 업무를 원하시나요?"),
    "채무조정 신청하고 싶어": json_output([action(1, "DEBT_ADJUSTMENT")]),
    "채무조정이 뭔지 설명해 줘": json_output([information(1, "DEBT_ADJUSTMENT")]),
    "대출 조회하고 거래내역도 보여줘": json_output([
        action(1, "LOAN_INQUIRY"), action(2, "TRANSACTION_SEARCH")]),
    "아니, 충당순서 변경 말고 조회만 할게": json_output([action(1, "LOAN_INQUIRY")]),
    "적금 하나 새로 가입하고 싶어": json_output(unsupported_reason="지원하지 않는 적금 신규 가입 요청입니다."),
    "대출 관련 업무 좀 처리하고 싶어": json_output(clarification="대출 조회, 충당순서 변경, 채무조정 중 어떤 업무를 원하시나요?"),
    "채무조정 신청하려면 어떤 서류가 필요해?": json_output([information(1, "DEBT_ADJUSTMENT")]),
    "채무조정 신청해 줘": json_output([action(1, "DEBT_ADJUSTMENT")]),
    "내 대출이 어떤 상태인지 알려줘": json_output([action(1, "LOAN_INQUIRY")]),
    "대출 상품에 대해 일반적으로 설명해 줘": json_output(
        unsupported_reason="지원 업무에 대응하지 않는 일반 대출 상품 설명 요청입니다."),
}


class IntentClassifierTests(unittest.TestCase):
    def setUp(self) -> None:
        self.client = StubIntentClient(CASES)
        self.classifier = IntentClassifier(self.client, EXECUTABLE_EXCEPT_B01)

    def test_ten_customer_messages_are_structured_without_executing_tools(self) -> None:
        results = {message: self.classifier.classify(message) for message in CASES}

        for message in list(CASES)[:3]:
            self.assertEqual(TaskCode.LOAN_INQUIRY, results[message].tasks[0].taskCode)
        self.assertEqual(TaskCode.REPAYMENT_ORDER_CHANGE, results["이자보다 원금부터 갚고 싶어"].tasks[0].taskCode)
        self.assertFalse(results["이자보다 원금부터 갚고 싶어"].tasks[0].executionAvailable)
        self.assertEqual([], results["이자보다 원금부터 갚고 싶어"].executionTargets)
        self.assertEqual(TaskCode.DEBT_ADJUSTMENT, results["채무조정 신청하고 싶어"].executionTargets[0])
        self.assertEqual(RequestKind.INFORMATION, results["채무조정이 뭔지 설명해 줘"].tasks[0].requestKind)
        self.assertEqual([], results["채무조정이 뭔지 설명해 줘"].executionTargets)
        self.assertEqual(
            [TaskCode.LOAN_INQUIRY, TaskCode.TRANSACTION_SEARCH],
            results["대출 조회하고 거래내역도 보여줘"].executionTargets,
        )
        self.assertEqual([TaskCode.LOAN_INQUIRY], results["아니, 충당순서 변경 말고 조회만 할게"].executionTargets)
        self.assertTrue(results["적금 하나 새로 가입하고 싶어"].unsupported)
        self.assertEqual([], results["적금 하나 새로 가입하고 싶어"].tasks)
        self.assertTrue(results["대출 관련 업무 좀 처리하고 싶어"].requiresClarification)
        self.assertEqual([], results["대출 관련 업무 좀 처리하고 싶어"].tasks)
        self.assertEqual(RequestKind.INFORMATION,
                         results["채무조정 신청하려면 어떤 서류가 필요해?"].tasks[0].requestKind)
        self.assertEqual([], results["채무조정 신청하려면 어떤 서류가 필요해?"].executionTargets)
        self.assertEqual([TaskCode.DEBT_ADJUSTMENT], results["채무조정 신청해 줘"].executionTargets)
        self.assertEqual([TaskCode.LOAN_INQUIRY], results["내 대출이 어떤 상태인지 알려줘"].executionTargets)
        self.assertTrue(results["대출 상품에 대해 일반적으로 설명해 줘"].unsupported)
        self.assertTrue(results["채무조정변경신청"].requiresClarification)
        self.assertEqual(TaskCode.DEBT_ADJUSTMENT, results["채무조정 신청하고 싶어"].tasks[0].taskCode)
        self.assertEqual(19, len(self.client.calls))

    def test_rejects_unknown_code_duplicate_code_broken_order_and_malformed_json(self) -> None:
        invalid_outputs = [
            json_output([action(1, "UNKNOWN_TASK")]),
            json_output([action(1, "LOAN_INQUIRY"), action(2, "LOAN_INQUIRY")]),
            json_output([action(2, "LOAN_INQUIRY")]),
            json.dumps({
                "tasks": [action(1, "LOAN_INQUIRY")],
                "requiresClarification": False, "clarificationQuestion": None,
                "unsupported": False, "unsupportedReason": None,
                "confidence": 0.99,
            }),
            json.dumps({
                "tasks": {"taskCode": "LOAN_INQUIRY"},
                "requiresClarification": False, "clarificationQuestion": None,
                "unsupported": False, "unsupportedReason": None,
            }),
            "not-json",
        ]
        for index, output in enumerate(invalid_outputs):
            classifier = IntentClassifier(StubIntentClient({str(index): output}), EXECUTABLE_EXCEPT_B01)
            with self.subTest(index=index), self.assertRaises(IntentOutputValidationError):
                classifier.classify(str(index))

    def test_missing_api_key_fails_before_an_llm_request(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(IntentProviderError, "INTENT_LLM_NOT_CONFIGURED"):
                OpenAiIntentClient().classify("내 대출 보여줘", [])

    def test_empty_or_whitespace_base_url_uses_official_default(self) -> None:
        with patch.dict(os.environ, {
            "OPENAI_API_KEY": "test-key",
            "OPENAI_BASE_URL": "   ",
        }, clear=True), patch("openai.OpenAI") as openai:
            openai.return_value.chat.completions.create.return_value.choices = [
                type("Choice", (), {"message": type("Message", (), {
                    "content": CASES["내 대출계좌 정보 조회해 줘"],
                })()})()
            ]

            result = OpenAiIntentClient().classify("내 대출계좌 정보 조회해 줘", [])

        self.assertEqual(CASES["내 대출계좌 정보 조회해 줘"], result)
        self.assertEqual("https://api.openai.com/v1", openai.call_args.kwargs["base_url"])

    def test_langgraph_node_shape_is_reusable_without_creating_a_work_item(self) -> None:
        node = self.classifier.as_langgraph_node()
        state_update = node({"message": "내 대출 전부 보여줘", "recentMessages": []})
        self.assertEqual("LOAN_INQUIRY", state_update["classification"]["tasks"][0]["taskCode"])

    def test_classification_endpoint_does_not_call_a_financial_task(self) -> None:
        registry = TaskRegistry([LoanInquiryTask(FakeSpringToolGateway())])
        app = FastAPI()
        app.include_router(create_router(registry, self.classifier), prefix="/api/v1")

        response = TestClient(app).post("/api/v1/customer-agent/classify", json={
            "message": "내 대출계좌 정보 조회해 줘",
        })

        self.assertEqual(200, response.status_code)
        self.assertEqual("LOAN_INQUIRY", response.json()["tasks"][0]["taskCode"])
        self.assertEqual(["LOAN_INQUIRY"], response.json()["executionTargets"])

    def test_invalid_llm_output_is_a_502_and_never_falls_back_to_execution(self) -> None:
        invalid = IntentClassifier(StubIntentClient({"bad": json.dumps({"tasks": []})}), EXECUTABLE_EXCEPT_B01)
        app = FastAPI()
        app.include_router(create_router(TaskRegistry(), invalid), prefix="/api/v1")

        response = TestClient(app).post("/api/v1/customer-agent/classify", json={"message": "bad"})

        self.assertEqual(502, response.status_code)
        self.assertEqual("INTENT_LLM_OUTPUT_INVALID", response.json()["detail"])


if __name__ == "__main__":
    unittest.main()
