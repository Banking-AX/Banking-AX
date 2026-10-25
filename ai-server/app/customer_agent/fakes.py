from __future__ import annotations

from typing import Any


class FakeSpringToolGateway:
    def b02_prepare(self, request: dict[str, Any]) -> dict[str, Any]: return {"taskCode":"HOUSING_SAVINGS_CLOSE","data":{"rate":"BASE_RATE","rule":"B2-R3","manual":False}}
    def b02_execute(self, request: dict[str, Any]) -> dict[str, Any]: return {"taskCode":"HOUSING_SAVINGS_CLOSE","data":{"status":"CLOSED"}}
    """Consumer-test fixture. It has no database or HTTP side effect."""
    def loan_inquiry(self, request: dict[str, Any]) -> dict[str, Any]:
        return {"requestId": request["requestId"], "taskCode": "LOAN_INQUIRY", "workItemId": request["workItemId"],
                "caseId": None, "revision": None,
                "data": {"loans": [{"loanId": 101, "loanNoMasked": "210-***-557731", "overdueDays": 45}]}, "error": None}

    def transaction_search(self, request: dict[str, Any]) -> dict[str, Any]:
        return {"requestId": request["requestId"], "taskCode": "TRANSACTION_SEARCH", "workItemId": request["workItemId"],
                "caseId": None, "revision": None, "error": None,
                "data": {"accountId": request["accountId"], "accountNoMasked": "110-***-000001", "accountName": "AX 입출금 통장",
                         "timeZone": "Asia/Seoul", "page": request.get("page", 0), "size": request.get("size", 20),
                         "totalElements": 0, "totalPages": 0, "transactions": []}}

    def debt_adjustment_start(self, request: dict[str, Any]) -> dict[str, Any]:
        return {"requestId": request["requestId"], "taskCode": "DEBT_ADJUSTMENT", "workItemId": 1201,
                "caseId": 2201, "revision": 0,
                "data": {"caseStatus": "DRAFT", "reviewStatus": None, "visitRequired": False,
                         "ruleEvaluation": {"submissionAllowed": True, "items": []}}, "error": None}

    def inheritance_deposit_prepare(self, request: dict[str, Any]) -> dict[str, Any]:
        return {"requestId": request["requestId"], "taskCode": "INHERITANCE_DEPOSIT", "workItemId": request["workItemId"],
                "caseId": 2301, "revision": 0, "data": {"caseView": {"visitRequired": True},
                "requiredDocuments": [], "submission": {"submittable": False, "errors": [], "outOfScope": [], "missingDocuments": []}}, "error": None}


class FakeT2Ports:
    """Phase 0 C-01/C-02 fixtures; never substitute for T2 implementations."""
    def document(self) -> dict[str, Any]: return {"status": "UPLOADED", "version": 1}
    def register_check(self, case_id: int, revision: int, task_code: str) -> dict[str, Any]:
        return {"caseId": case_id, "revision": revision, "taskCode": task_code,
                "status": "SUCCEEDED", "reviewStatus": "READY_FOR_STAFF", "reviewItems": [], "error": None}
    def notification(self) -> dict[str, Any]: return {"eventId": "fake-notification", "deduplicated": False}
    def visit(self) -> dict[str, Any]: return {"status": "NOT_IMPLEMENTED_BY_T1"}
