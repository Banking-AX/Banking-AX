"""Declared employee-check registration; no package auto-imports."""
from __future__ import annotations

from collections.abc import Callable

from .models import CheckRequest, ReviewItem

CheckHandler = Callable[[CheckRequest], list[ReviewItem]]


class CheckRegistry:
    def __init__(self) -> None:
        self._handlers: dict[tuple[str, str], CheckHandler] = {}

    def register(self, task_code: str, check_type: str, handler: CheckHandler) -> None:
        key = (task_code, check_type)
        if key in self._handlers:
            raise ValueError(f'check already registered: {task_code}/{check_type}')
        self._handlers[key] = handler

    def execute(self, request: CheckRequest) -> list[ReviewItem]:
        try:
            return self._handlers[(request.task_code, request.check_type)](request)
        except KeyError as exc:
            raise LookupError('CHECK_NOT_REGISTERED') from exc
