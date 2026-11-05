from __future__ import annotations

from typing import Any

from ..contracts import TaskDefinition


class RepaymentOrderChangeTask:
    """Registers B-01 without granting the Agent execution authority."""

    definition = TaskDefinition("REPAYMENT_ORDER_CHANGE", "채무변제 충당순서 변경", ())

    def run(self, request: dict[str, Any]) -> dict[str, Any]:
        return {"taskCode": self.definition.task_code, "screenOnly": True}
