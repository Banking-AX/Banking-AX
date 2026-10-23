from __future__ import annotations

from typing import Any


class FakeSpringToolGateway:
    """Consumer-test fixture. It has no database or HTTP side effect."""
    def loan_inquiry(self, request: dict[str, Any]) -> dict[str, Any]:
        return {"requestId": request["requestId"], "taskCode": "LOAN_INQUIRY", "workItemId": request["workItemId"],
                "caseId": None, "revision": None,
                "data": {"loans": [{"loanId": 101, "loanNoMasked": "210-***-557731", "overdueDays": 45}]}, "error": None}


class FakeT2Ports:
    """Phase 0 C-01/C-02 fixtures; never substitute for T2 implementations."""
    def document(self) -> dict[str, Any]: return {"status": "UPLOADED", "version": 1}
    def register_check(self, case_id: int, revision: int, task_code: str) -> dict[str, Any]:
        return {"caseId": case_id, "revision": revision, "taskCode": task_code, "status": "READY_FOR_STAFF", "reviewItems": []}
    def notification(self) -> dict[str, Any]: return {"eventId": "fake-notification", "deduplicated": False}
    def visit(self) -> dict[str, Any]: return {"status": "NOT_IMPLEMENTED_BY_T1"}
