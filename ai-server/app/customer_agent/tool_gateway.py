from __future__ import annotations

import json
import os
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class SpringHttpToolGateway:
    def workflow_start(self, request: dict[str, Any]) -> dict[str, Any]:
        customer_id = request["customerId"]
        token = os.getenv("SPRING_TOOL_GATEWAY_TOKEN")
        if not token:
            raise RuntimeError("SPRING_TOOL_GATEWAY_TOKEN_MISSING")
        headers = {"Content-Type": "application/json", "X-Actor-Id": str(customer_id),
                   "X-Actor-Role": "CUSTOMER", "X-Tool-Gateway-Token": token}
        payload = {key: request[key] for key in ("requestId", "customerId", "taskCode")}
        return self._post("/api/v1/tools/workflows/start", payload, headers)

    def b02_prepare(self, request: dict[str, Any]) -> dict[str, Any]:
        return self._b02("/api/v1/b02/prepare", request, ("requestId","customerId","accountId","waivePreferredRate","taxed"))
    def b02_execute(self, request: dict[str, Any]) -> dict[str, Any]:
        return self._b02(f"/api/v1/b02/{request['accountId']}/execute", request, ("requestId","customerId","version","waivePreferredRate","taxed","bindingId"))
    def _b02(self, path: str, request: dict[str, Any], fields: tuple[str, ...]) -> dict[str, Any]:
        token=os.getenv("SPRING_TOOL_GATEWAY_TOKEN")
        if not token: raise RuntimeError("SPRING_TOOL_GATEWAY_TOKEN_MISSING")
        payload={"requestId":request["requestId"],"customer": request["customerId"], "account": request["accountId"], "waive": request.get("waivePreferredRate",False), "taxed":request.get("taxed")} if path.endswith("prepare") else {"customer":request["customerId"],"version":request.get("version",0),"waive":request.get("waivePreferredRate",False),"taxed":request.get("taxed"),"binding":request.get("bindingId"),"requestId":request["requestId"]}
        headers={"Content-Type":"application/json","X-Actor-Id":str(request["customerId"]),"X-Actor-Role":"CUSTOMER","X-Tool-Gateway-Token":token}
        return self._post(path,payload,headers)
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

    def transaction_search(self, request: dict[str, Any]) -> dict[str, Any]:
        customer_id = request["customerId"]
        token = os.getenv("SPRING_TOOL_GATEWAY_TOKEN")
        if not token:
            raise RuntimeError("SPRING_TOOL_GATEWAY_TOKEN_MISSING")
        headers = {"Content-Type": "application/json", "X-Actor-Id": str(customer_id), "X-Actor-Role": "CUSTOMER", "X-Tool-Gateway-Token": token}
        payload = {key: request[key] for key in ("requestId", "workItemId", "customerId", "accountId", "startDate", "endDate", "transactionType", "minimumAmount", "maximumAmount", "keyword", "sortOrder", "page", "size") if request.get(key) is not None}
        return self._post("/api/v1/tools/transaction-search", payload, headers)

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
        payload = {key: request[key] for key in ("customerId", "workItemId", "requestId", "registeredPhone", "decedent", "heirs", "documentStatuses") if key in request}
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
