from __future__ import annotations

import os
import sqlite3
from pathlib import Path
from types import TracebackType

from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from langgraph.checkpoint.sqlite import SqliteSaver


def default_b01_checkpoint_path() -> Path:
    configured = os.getenv("CUSTOMER_AGENT_B01_CHECKPOINT_PATH", "").strip()
    if configured:
        return Path(configured).expanduser().resolve()
    return Path(__file__).resolve().parents[2] / ".state" / "b01-checkpoints.sqlite3"


class B01CheckpointStore:
    """Owns one SQLite connection for B-01 LangGraph checkpoint persistence."""

    def __init__(self, path: str | Path | None = None) -> None:
        self.path = Path(path).expanduser().resolve() if path else default_b01_checkpoint_path()
        self._connection: sqlite3.Connection | None = None
        self._saver: SqliteSaver | None = None

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

    def __enter__(self) -> "B01CheckpointStore":
        return self.open()

    def __exit__(self, exc_type: type[BaseException] | None,
                 exc_value: BaseException | None,
                 traceback: TracebackType | None) -> None:
        self.close()
