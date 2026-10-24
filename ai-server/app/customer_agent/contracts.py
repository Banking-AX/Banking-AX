from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol


@dataclass(frozen=True)
class TaskDefinition:
    task_code: str
    name: str
    allowed_tools: tuple[str, ...]


class CustomerTask(Protocol):
    definition: TaskDefinition

    def run(self, request: dict[str, Any]) -> dict[str, Any]: ...


class SpringToolGateway(Protocol):
    def loan_inquiry(self, request: dict[str, Any]) -> dict[str, Any]: ...
    def debt_adjustment_start(self, request: dict[str, Any]) -> dict[str, Any]: ...
    def inheritance_deposit_prepare(self, request: dict[str, Any]) -> dict[str, Any]: ...
