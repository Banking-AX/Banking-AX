from __future__ import annotations

from datetime import date
from decimal import Decimal, InvalidOperation
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
        for field in ("workItemId", "customerId", "accountId"):
            value = request[field]
            if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                raise ValueError(f"{field} must be a positive integer")
        try:
            start = date.fromisoformat(str(request["startDate"]))
            end = date.fromisoformat(str(request["endDate"]))
        except ValueError as exc:
            raise ValueError("startDate and endDate must use YYYY-MM-DD") from exc
        if start > end:
            raise ValueError("startDate must be on or before endDate")
        if request.get("transactionType") not in (None, "IN", "OUT"):
            raise ValueError("transactionType must be IN or OUT")
        if request.get("sortOrder") not in (None, "RECENT", "OLDEST"):
            raise ValueError("sortOrder must be RECENT or OLDEST")
        for field in ("page", "size"):
            value = request.get(field)
            if value is not None and (isinstance(value, bool) or not isinstance(value, int) or value < 0 or field == "size" and value == 0):
                raise ValueError(f"{field} must be a positive integer" if field == "size" else "page must be a non-negative integer")
        amounts: dict[str, Decimal] = {}
        for field in ("minimumAmount", "maximumAmount"):
            value = request.get(field)
            if value is None:
                continue
            try:
                amount = Decimal(str(value))
            except (InvalidOperation, ValueError) as exc:
                raise ValueError(f"{field} must be a decimal amount") from exc
            if not amount.is_finite() or amount < 0:
                raise ValueError(f"{field} must be a non-negative decimal amount")
            amounts[field] = amount
        if amounts.get("minimumAmount") is not None and amounts.get("maximumAmount") is not None and amounts["minimumAmount"] > amounts["maximumAmount"]:
            raise ValueError("minimumAmount must not exceed maximumAmount")
