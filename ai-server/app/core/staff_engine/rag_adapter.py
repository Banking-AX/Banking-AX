"""Adapter for the existing RAG function; it introduces no second index."""
from __future__ import annotations

from collections.abc import Callable

from .models import PolicyEvidence


SearchCallable = Callable[[str, str, int, str], list[dict]]


def existing_rag_search(query: str, task_code: str, top_k: int, audience: str) -> list[dict]:
    from app.retrieve import search
    return search(query, task_code, top_k, audience)


def keyword_rag_search(query: str, task_code: str, top_k: int, audience: str) -> list[dict]:
    """Same index, lexical strategy only: no embedding model load, so it fits Spring's short Agent timeout."""
    from app.retrieve import search_with_strategy
    return search_with_strategy(query, task_code, top_k, audience, 'keyword')


class RagEvidenceAdapter:
    def __init__(self, search_callable: SearchCallable = existing_rag_search) -> None:
        self._search = search_callable

    def search(self, query: str, task_code: str, audience: str = 'staff') -> list[PolicyEvidence]:
        rows = self._search(query, task_code, 3, audience)
        return [PolicyEvidence(
            doc_id=row['doc_id'], title=row['title'], section=row['section'],
            source_pages=list(row['source_pages']), verified=bool(row['verified']),
            text=row['text'],
        ) for row in rows]
