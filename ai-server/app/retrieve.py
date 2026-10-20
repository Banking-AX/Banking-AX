"""Task-filtered hybrid retrieval for curated financial-document chunks.

The public ``score`` for the Chroma backend is an RRF ranking score, not a
cosine similarity.  This keeps the API shape stable without implying that
lexical and vector scores are directly comparable.
"""
from __future__ import annotations

import json
import math
import re
from collections import Counter, defaultdict
from functools import lru_cache

from .settings import (
    BACKEND, HYBRID_EVIDENCE_RRF_WEIGHT, HYBRID_LEXICAL_CANDIDATES,
    HYBRID_MAX_PER_SECTION, HYBRID_RRF_K, HYBRID_VECTOR_CANDIDATES, INDEX,
    MODEL, TASKS, VECTOR,
)

TERM = re.compile(r'[가-힣a-zA-Z0-9]+')
PARTICLES = ('으로부터', '에서', '에게', '까지', '부터', '으로', '은', '는', '이', '가', '을', '를', '에', '의', '도', '와', '과', '만')
EVIDENCE_TERMS = ('서류', '증명서', '등본', '신분증', '동의서', '위임장', '신청서', '입증', '증빙')
BROAD_EVIDENCE_TERMS = ('서류', '입증', '증빙')
# This is response-only context.  It is never embedded or used as a Chroma
# document, so the model's 128-token embedding limit remains unchanged.
CONTEXT_MAX_CHARS = 1600


def _grams(text: str) -> set[str]:
    compact = re.sub(r'[^가-힣a-zA-Z0-9]', '', text.lower())
    return ({compact[i:i + 2] for i in range(max(0, len(compact) - 1))}
            | {compact[i:i + 3] for i in range(max(0, len(compact) - 2))})


def _terms(text: str) -> list[str]:
    """Korean-friendly lexical units; punctuation and spacing do not matter."""
    terms = []
    for raw in TERM.findall(text):
        term = raw.lower()
        if len(term) > 1:
            terms.append(term)
        for particle in PARTICLES:
            if term.endswith(particle) and len(term) - len(particle) > 1:
                terms.append(term[:-len(particle)])
                break
    return terms


@lru_cache(maxsize=1)
def _embed_model():
    from sentence_transformers import SentenceTransformer
    return SentenceTransformer(MODEL)


@lru_cache(maxsize=1)
def _client():
    import chromadb
    return chromadb.PersistentClient(path=str(VECTOR))


def _visible(row: dict, task_code: str, audience: str) -> bool:
    return row['task_code'] == task_code and (row['audience'] == 'CUSTOMER_AND_STAFF' or audience == 'staff')


def _load_visible(task_code: str, audience: str) -> list[dict]:
    if not INDEX.exists():
        raise RuntimeError('Index missing. Run: python -m scripts.ingest')
    entries = json.loads(INDEX.read_text(encoding='utf8'))
    return [entry for entry in entries if _visible(entry, task_code, audience)]


def _keyword_ranked(query: str, entries: list[dict]) -> list[tuple[dict, float]]:
    """BM25 exact-term ranking, with character n-grams for Korean spacing variants."""
    query_terms = _terms(query)
    if not query_terms:
        return []
    documents = [_terms(entry['text']) for entry in entries]
    doc_frequency = Counter(term for terms in documents for term in set(terms))
    average_length = sum(len(terms) for terms in documents) / max(1, len(documents))
    query_counts = Counter(query_terms)
    query_grams = _grams(query)
    asks_for_evidence = any(term in query for term in EVIDENCE_TERMS)
    total = len(entries)
    ranked: list[tuple[dict, float]] = []
    for entry, terms in zip(entries, documents):
        frequencies = Counter(terms)
        length = max(1, len(terms))
        bm25 = 0.0
        for term, query_count in query_counts.items():
            frequency = frequencies[term]
            if not frequency:
                continue
            idf = math.log(1 + (total - doc_frequency[term] + 0.5) / (doc_frequency[term] + 0.5))
            bm25 += query_count * idf * (frequency * 2.0 / (frequency + 1.0 * (1 - 0.75 + 0.75 * length / average_length)))
        section_terms = Counter(_terms(entry['section']))
        section_boost = sum(query_counts[term] for term in section_terms) * 0.7
        # A Korean particle or compound often hides an exact financial term:
        # "서류는" should still match "입증서류" without a domain-specific synonym list.
        compound_bonus = 0.0
        unique_terms = set(terms)
        for query_term in query_counts:
            if len(query_term) < 2:
                continue
            if any(query_term in term or term in query_term for term in unique_terms):
                partial_df = sum(any(query_term in item or item in query_term for item in set(other)) for other in documents)
                compound_bonus += 0.55 * math.log(1 + (total - partial_df + 0.5) / (partial_df + 0.5))
        # Information-type intent is reusable across all tasks: a question asking
        # for documents should favor chunks that name concrete evidence, not a
        # merely adjacent condition or exception.
        evidence_bonus = 0.0
        if asks_for_evidence:
            evidence_bonus = min(4, sum(term in entry['text'] for term in EVIDENCE_TERMS)) * 1.5
        grams = _grams(entry['text'])
        spacing_score = len(query_grams & grams) / (math.sqrt(len(query_grams) * len(grams)) or 1)
        score = bm25 + section_boost + compound_bonus + evidence_bonus + 0.25 * spacing_score
        if score > 0:
            ranked.append((entry, score))
    return sorted(ranked, key=lambda item: (-item[1], item[0]['chunk_id']))


def _vector_ranked(query: str, task_code: str, entries: list[dict], candidate_count: int) -> list[tuple[dict, float]]:
    model = _embed_model()
    vector = model.encode([query], normalize_embeddings=True)[0].tolist()
    result = _client().get_collection('shinhan_knowledge').query(
        query_embeddings=[vector], n_results=min(len(entries), candidate_count),
        where={'task_code': task_code}, include=['distances'],
    )
    visible = {entry['chunk_id']: entry for entry in entries}
    ranked = []
    for chunk_id, distance in zip(result['ids'][0], result['distances'][0]):
        if chunk_id in visible:
            ranked.append((visible[chunk_id], max(0.0, 1 - float(distance))))
    return ranked


def _information_type_ranked(query: str, entries: list[dict]) -> list[tuple[dict, float]]:
    """Rank concrete evidence chunks when the question itself asks for documents."""
    if not any(term in query for term in BROAD_EVIDENCE_TERMS):
        return []
    ranked = []
    for entry in entries:
        count = sum(term in entry['text'] for term in EVIDENCE_TERMS)
        if count:
            ranked.append((entry, float(count)))
    return sorted(ranked, key=lambda item: (-item[1], item[0]['chunk_id']))


def _rrf(*rankings: tuple[list[tuple[dict, float]], int]) -> list[tuple[dict, float]]:
    """Fuse independent ranks; do not add cosine and lexical scores directly."""
    fused: dict[str, tuple[dict, float]] = {}
    for ranked, weight in rankings:
        for rank, (entry, _) in enumerate(ranked, start=1):
            previous = fused.get(entry['chunk_id'])
            score = (previous[1] if previous else 0.0) + weight / (HYBRID_RRF_K + rank)
            fused[entry['chunk_id']] = (entry, score)
    return sorted(fused.values(), key=lambda item: (-item[1], item[0]['chunk_id']))


def _diversify(ranked: list[tuple[dict, float]], top_k: int) -> list[tuple[dict, float]]:
    """Avoid overfilling Top-K with one section while retaining a second contiguous chunk."""
    selected, overflow = [], []
    per_section: dict[tuple[str, str], int] = defaultdict(int)
    for entry, score in ranked:
        key = (entry['doc_id'], entry['section'])
        if per_section[key] < HYBRID_MAX_PER_SECTION:
            selected.append((entry, score))
            per_section[key] += 1
            if len(selected) == top_k:
                return selected
        else:
            overflow.append((entry, score))
    return (selected + overflow)[:top_k]


def _body_size(entry: dict) -> int:
    """Prefer a section's actual content over its heading-only chunk."""
    body = entry['text'].partition('\n\n')[2]
    return len(re.sub(r'^\s*#+\s*.*$', '', body, flags=re.M).strip())


def _prefer_substantive_anchors(ranked: list[tuple[dict, float]], entries: list[dict]) -> list[tuple[dict, float]]:
    """Make section retrieval point at a substantive sibling, when available.

    Chunking may create a small heading-only chunk followed by the section body.
    The heading carries title metadata and can rank well despite containing no
    answer.  Replacing only such anchors with a sibling from the same document,
    task, and section is a corpus-agnostic section-level ranking correction.
    """
    siblings: dict[tuple[str, str, str], list[dict]] = defaultdict(list)
    positions = {entry['chunk_id']: index for index, entry in enumerate(entries)}
    for entry in entries:
        siblings[(entry['doc_id'], entry['task_code'], entry['section'])].append(entry)
    promoted = []
    for entry, score in ranked:
        key = (entry['doc_id'], entry['task_code'], entry['section'])
        candidate = max(siblings[key], key=lambda item: (_body_size(item), item['chunk_id']))
        # A Markdown document title can become an empty ``개요`` chunk directly
        # before its first real section.  Treat that heading as a pointer to the
        # adjacent substantive section instead of returning a title as evidence.
        if _body_size(candidate) == 0:
            position = positions[entry['chunk_id']]
            for neighbor_index in (position + 1, position - 1):
                if 0 <= neighbor_index < len(entries):
                    neighbor = entries[neighbor_index]
                    if (neighbor['doc_id'] == entry['doc_id'] and neighbor['task_code'] == entry['task_code']
                            and _body_size(neighbor) > 0):
                        candidate = neighbor
                        break
        promoted.append((candidate if _body_size(candidate) > _body_size(entry) else entry, score))
    # A promoted sibling can coincide with an independently ranked candidate.
    # Keep its best fusion score without duplicating a response row.
    best: dict[str, tuple[dict, float]] = {}
    for entry, score in promoted:
        previous = best.get(entry['chunk_id'])
        if previous is None or score > previous[1]:
            best[entry['chunk_id']] = (entry, score)
    return sorted(best.values(), key=lambda item: (-item[1], item[0]['chunk_id']))


def _with_context(entry: dict, score: float, entries: list[dict]) -> dict:
    """Attach bounded, source-preserving neighbor context for one section."""
    siblings = [item for item in entries if item['doc_id'] == entry['doc_id']
                and item['task_code'] == entry['task_code'] and item['section'] == entry['section']]
    position = next(index for index, item in enumerate(siblings) if item['chunk_id'] == entry['chunk_id'])
    selected = [position]
    total = len(entry['text'])
    left, right = position - 1, position + 1
    while left >= 0 or right < len(siblings):
        progressed = False
        for index in (left, right):
            if 0 <= index < len(siblings):
                addition = len(siblings[index]['text']) + len('\n\n--- 인접 청크 ---\n\n')
                if total + addition <= CONTEXT_MAX_CHARS:
                    selected.append(index)
                    total += addition
                    progressed = True
        left -= 1
        right += 1
        if not progressed:
            break
    selected.sort()
    context_entries = [siblings[index] for index in selected]
    return dict(entry, score=round(score, 6),
                context_text='\n\n--- 인접 청크 ---\n\n'.join(item['text'] for item in context_entries),
                context_chunk_ids=[item['chunk_id'] for item in context_entries],
                context_sections=[item['section'] for item in context_entries])


def _validate(task_code: str, audience: str) -> None:
    if task_code not in TASKS:
        raise ValueError('Unsupported task_code')
    if audience not in ('customer', 'staff'):
        raise ValueError('audience must be customer/staff')


def search_with_strategy(query: str, task_code: str, top_k: int = 4, audience: str = 'customer', strategy: str = 'hybrid') -> list[dict]:
    """Internal evaluator entry point. ``search`` remains the API-facing function."""
    _validate(task_code, audience)
    entries = _load_visible(task_code, audience)
    if not entries:
        return []
    if strategy == 'keyword':
        ranked = _prefer_substantive_anchors(_keyword_ranked(query, entries), entries)[:top_k]
        return [_with_context(entry, score, entries) for entry, score in ranked]
    vector_ranked = _vector_ranked(query, task_code, entries, max(HYBRID_VECTOR_CANDIDATES, top_k))
    if strategy == 'vector':
        ranked = _prefer_substantive_anchors(vector_ranked, entries)[:top_k]
        return [_with_context(entry, score, entries) for entry, score in ranked]
    if strategy != 'hybrid':
        raise ValueError('strategy must be keyword, vector, or hybrid')
    lexical_ranked = _keyword_ranked(query, entries)[:HYBRID_LEXICAL_CANDIDATES]
    information_type_ranked = _information_type_ranked(query, entries)[:HYBRID_LEXICAL_CANDIDATES]
    fused = _prefer_substantive_anchors(_rrf(
        (vector_ranked, 1), (lexical_ranked, 1),
        (information_type_ranked, HYBRID_EVIDENCE_RRF_WEIGHT),
    ), entries)
    return [_with_context(entry, score, entries) for entry, score in _diversify(fused, top_k)]


def search(query: str, task_code: str, top_k: int = 4, audience: str = 'customer') -> list[dict]:
    """Return task- and audience-filtered results without changing API response fields."""
    strategy = 'keyword' if BACKEND == 'keyword' else 'hybrid' if BACKEND == 'chroma' else None
    if strategy is None:
        raise RuntimeError('RAG_BACKEND must be keyword or chroma')
    return search_with_strategy(query, task_code, top_k, audience, strategy)
