from __future__ import annotations

from collections.abc import Iterable
from .contracts import CustomerTask


class TaskRegistry:
    """Explicit registration prevents side-effectful task discovery imports."""
    def __init__(self, tasks: Iterable[CustomerTask] = ()) -> None:
        self._tasks: dict[str, CustomerTask] = {}
        for task in tasks:
            self.register(task)

    def register(self, task: CustomerTask) -> None:
        code = task.definition.task_code
        if code in self._tasks:
            raise ValueError(f"duplicate task registration: {code}")
        self._tasks[code] = task

    def get(self, task_code: str) -> CustomerTask:
        try:
            return self._tasks[task_code]
        except KeyError as exc:
            raise ValueError(f"unsupported taskCode: {task_code}") from exc

    def definitions(self) -> list[dict[str, object]]:
        return [{"taskCode": task.definition.task_code, "name": task.definition.name,
                 "allowedTools": list(task.definition.allowed_tools)} for task in self._tasks.values()]
