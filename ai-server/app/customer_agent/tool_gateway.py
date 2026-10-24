from __future__ import annotations

import json
import os
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class SpringHttpToolGateway:
    """T1 HTTP client. It forwards no OTP or customer-document contents."""
    def __init__(self, base_url: str | None = None, timeout_seconds: float = 5.0) -> None:
        self.base_url = (base_url or os.getenv("SPRING_TOOL_BASE_URL", "http://localhost:8080")).rstrip("/")
        self.timeout_seconds = timeout_seconds

    def loan_inquiry(self, request: dict[str, Any]) -> dict[str, Any]:
        customer_id = request["customerId"]
        token = os.getenv("SPRING_TOOL_GATEWAY_TOKEN")
        if not token:
            raise RuntimeError("SPRING_TOOL_GATEWAY_TOKEN_MISSING")
        headers = {"Content-Type": "application/json", "X-Actor-Id": str(customer_id), "X-Actor-Role": "CUSTOMER", "X-Tool-Gateway-Token": token}
        return self._post("/api/v1/tools/loan-inquiry", request, headers)

    def debt_adjustment_start(self, request: dict[str, Any]) -> dict[str, Any]:
        customer_id = request["customerId"]
        token = os.getenv("SPRING_TOOL_GATEWAY_TOKEN")
        if not token:
            raise RuntimeError("SPRING_TOOL_GATEWAY_TOKEN_MISSING")
        headers = {"Content-Type": "application/json", "X-Actor-Id": str(customer_id), "X-Actor-Role": "CUSTOMER", "X-Tool-Gateway-Token": token}
        payload = {key: request[key] for key in ("customerId", "workflowId", "expectedWorkflowVersion", "requestId", "loanId", "registeredPhone") if key in request}
        return self._post("/api/v1/c01/start", payload, headers)

    def inheritance_deposit_prepare(self, request: dict[str, Any]) -> dict[str, Any]:
        customer_id = request["customerId"]
        token = os.getenv("SPRING_TOOL_GATEWAY_TOKEN")
        if not token:
            raise RuntimeError("SPRING_TOOL_GATEWAY_TOKEN_MISSING")
        headers = {"Content-Type": "application/json", "X-Actor-Id": str(customer_id), "X-Actor-Role": "CUSTOMER", "X-Tool-Gateway-Token": token}
        payload = {key: request[key] for key in ("customerId", "workItemId", "requestId", "decedent", "heirs", "documentStatuses") if key in request}
        return self._post("/api/v1/c02/prepare", payload, headers)

    def _post(self, path: str, payload: dict[str, Any], headers: dict[str, str]) -> dict[str, Any]:
        http_request = Request(f"{self.base_url}{path}", data=json.dumps(payload).encode(), headers=headers, method="POST")
        try:
            with urlopen(http_request, timeout=self.timeout_seconds) as response:
                return json.loads(response.read().decode())
        except HTTPError as exc:
            body = exc.read().decode(errors="replace")
            raise RuntimeError(f"SPRING_TOOL_HTTP_{exc.code}: {body}") from exc
        except URLError as exc:
            raise RuntimeError("SPRING_TOOL_UNAVAILABLE") from exc
