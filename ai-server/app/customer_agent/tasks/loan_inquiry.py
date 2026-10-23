from __future__ import annotations

from typing import Any, TypedDict
from ..contracts import SpringToolGateway, TaskDefinition


class LoanInquiryState(TypedDict, total=False):
    request: dict[str, Any]
    response: dict[str, Any]


class LoanInquiryTask:
    definition = TaskDefinition("LOAN_INQUIRY", "대출계좌 정보 조회", ("loan_inquiry",))

    def __init__(self, gateway: SpringToolGateway) -> None:
        self.gateway = gateway

    def run(self, request: dict[str, Any]) -> dict[str, Any]:
        self._validate(request)
        return self._graph().invoke({"request": request})["response"]

    def _graph(self):
        # Delayed import keeps the router importable until T1's dedicated extras are installed.
        from langgraph.graph import END, START, StateGraph
        graph = StateGraph(LoanInquiryState)
        graph.add_node("lookup", lambda state: {"response": self.gateway.loan_inquiry(state["request"])})
        graph.add_edge(START, "lookup")
        graph.add_edge("lookup", END)
        return graph.compile()

    @staticmethod
    def _validate(request: dict[str, Any]) -> None:
        for field in ("requestId", "workItemId", "customerId"):
            if request.get(field) in (None, ""):
                raise ValueError(f"{field} is required")
