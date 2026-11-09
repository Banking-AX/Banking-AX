from __future__ import annotations

import os
import sqlite3
import threading
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from types import TracebackType
from typing import Iterator

from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from langgraph.checkpoint.sqlite import SqliteSaver


def default_b01_checkpoint_path() -> Path:
    configured = os.getenv("CUSTOMER_AGENT_B01_CHECKPOINT_PATH", "").strip()
    if configured:
        return Path(configured).expanduser().resolve()
    return Path(__file__).resolve().parents[2] / ".state" / "b01-checkpoints.sqlite3"


@dataclass(frozen=True)
class PendingB01Thread:
    work_item_id: int
    state: dict[str, object]


class B01CheckpointStore:
    """Owns one SQLite connection for B-01 LangGraph checkpoint persistence."""

    def __init__(self, path: str | Path | None = None) -> None:
        self.path = Path(path).expanduser().resolve() if path else default_b01_checkpoint_path()
        self._connection: sqlite3.Connection | None = None
        self._saver: SqliteSaver | None = None
        self._locks_guard = threading.Lock()
        self._thread_locks: dict[int, threading.RLock] = {}

    @property
    def is_open(self) -> bool:
        return self._connection is not None

    @property
    def saver(self) -> SqliteSaver:
        if self._saver is None:
            raise RuntimeError("B01_CHECKPOINT_STORE_NOT_OPEN")
        return self._saver

    def open(self) -> "B01CheckpointStore":
        if self._connection is not None:
            return self
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.path.parent, 0o700)
        connection = sqlite3.connect(self.path, check_same_thread=False)
        os.chmod(self.path, 0o600)
        self._connection = connection
        # State is limited to JSON-compatible primitives. Explicitly prohibit
        # pickle fallback when checkpoints are deserialized.
        self._saver = SqliteSaver(
            connection,
            serde=JsonPlusSerializer(pickle_fallback=False),
        )
        return self

    def close(self) -> None:
        if self._connection is not None:
            self._connection.close()
        self._connection = None
        self._saver = None

    def find_pending(self, customer_id: int, chat_room_id: int) -> PendingB01Thread | None:
        return self._find(customer_id, chat_room_id, pending_only=True)

    def find_request(self, customer_id: int, chat_room_id: int,
                     request_id: str) -> PendingB01Thread | None:
        return self._find(customer_id, chat_room_id, request_id=request_id)

    def _find(self, customer_id: int, chat_room_id: int,
              request_id: str | None = None,
              pending_only: bool = False) -> PendingB01Thread | None:
        seen_threads: set[str] = set()
        matches: list[PendingB01Thread] = []
        for checkpoint_tuple in self.saver.list(None):
            configurable = checkpoint_tuple.config.get("configurable", {})
            thread_id = str(configurable.get("thread_id", ""))
            if not thread_id.startswith("b01:") or thread_id in seen_threads:
                continue
            seen_threads.add(thread_id)
            values = checkpoint_tuple.checkpoint.get("channel_values", {})
            if (values.get("customer_id") != customer_id
                    or values.get("chat_room_id") != chat_room_id):
                continue
            if request_id is not None and values.get("last_operation_request_id") != request_id:
                continue
            if (pending_only and (values.get("awaiting_input") is None
                                  or not values.get("pending_question"))):
                continue
            try:
                work_item_id = int(thread_id.removeprefix("b01:"))
            except ValueError:
                continue
            matches.append(PendingB01Thread(work_item_id, dict(values)))
        if len(matches) > 1:
            raise RuntimeError("B01_MULTIPLE_PENDING_THREADS")
        return matches[0] if matches else None

    @contextmanager
    def execution_lock(self, work_item_id: int) -> Iterator[None]:
        with self._locks_guard:
            lock = self._thread_locks.setdefault(work_item_id, threading.RLock())
        with lock:
            yield

    def __enter__(self) -> "B01CheckpointStore":
        return self.open()

    def __exit__(self, exc_type: type[BaseException] | None,
                 exc_value: BaseException | None,
                 traceback: TracebackType | None) -> None:
        self.close()
