"""T1 C-01 customer task. It starts intake only; submission is a customer-explicit Spring action."""
from __future__ import annotations

from typing import Any, TypedDict

from ..contracts import SpringToolGateway, TaskDefinition


class DebtAdjustmentState(TypedDict, total=False):
    request: dict[str, Any]
    response: dict[str, Any]


class DebtAdjustmentTask:
    definition = TaskDefinition("DEBT_ADJUSTMENT", "채무조정 접수", ("debt_adjustment_start",))

    def __init__(self, gateway: SpringToolGateway) -> None:
        self.gateway = gateway

    def run(self, request: dict[str, Any]) -> dict[str, Any]:
        for field in ("requestId", "customerId", "workflowId", "loanId"):
            if request.get(field) in (None, ""):
                raise ValueError(f"{field} is required")
        return self._graph().invoke({"request": request})["response"]

    def _graph(self):
        from langgraph.graph import END, START, StateGraph
        graph = StateGraph(DebtAdjustmentState)
        graph.add_node("start_intake", lambda state: {"response": self.gateway.debt_adjustment_start(state["request"])})
        graph.add_edge(START, "start_intake")
        graph.add_edge("start_intake", END)
        return graph.compile()
