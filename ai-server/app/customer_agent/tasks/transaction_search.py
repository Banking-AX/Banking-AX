from __future__ import annotations

from typing import Any, TypedDict
from ..contracts import SpringToolGateway, TaskDefinition


class TransactionSearchState(TypedDict, total=False):
    request: dict[str, Any]
    response: dict[str, Any]


class TransactionSearchTask:
    definition = TaskDefinition("TRANSACTION_SEARCH", "거래내역 조회", ("transaction_search",))

    def __init__(self, gateway: SpringToolGateway) -> None:
        self.gateway = gateway

    def run(self, request: dict[str, Any]) -> dict[str, Any]:
        self._validate(request)
        # The graph only forwards structured conditions. It never invents transactions.
        return self._graph().invoke({"request": request})["response"]

    def _graph(self):
        from langgraph.graph import END, START, StateGraph
        graph = StateGraph(TransactionSearchState)
        graph.add_node("lookup", lambda state: {"response": self.gateway.transaction_search(state["request"])})
        graph.add_edge(START, "lookup")
        graph.add_edge("lookup", END)
        return graph.compile()

    @staticmethod
    def _validate(request: dict[str, Any]) -> None:
        for field in ("requestId", "workItemId", "customerId", "accountId", "startDate", "endDate"):
            if request.get(field) in (None, ""):
                raise ValueError(f"{field} is required")
