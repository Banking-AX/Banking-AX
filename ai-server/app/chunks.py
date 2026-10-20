"""Build deterministic, tokenizer-bounded chunks from curated Markdown documents."""
from __future__ import annotations

import hashlib
import re
from functools import lru_cache

import yaml

from .settings import MODEL, ROOT

HEAD = re.compile(r'^(#{2,4})\s+(.+?)\s*$', re.M)
FRONT = re.compile(r'\A---\s*\n(.*?)\n---\s*\n', re.S)
MAX_TOKENS = 128
TARGET_TOKENS = 110


@lru_cache(maxsize=1)
def _tokenizer():
    """Use the configured SentenceTransformer tokenizer, not a character proxy."""
    from sentence_transformers import SentenceTransformer
    return SentenceTransformer(MODEL).tokenizer


def token_count(text: str) -> int:
    """Count exactly what the embedding model receives, including special tokens."""
    return len(_tokenizer()(text, add_special_tokens=True, truncation=False, verbose=False)['input_ids'])


def _chunk_text(title: str, task: str, section: str, body: str) -> str:
    return f"문서: {title}\n업무: {task}\n항목: {section}\n\n{body.strip()}"


def _sentence_units(text: str) -> list[str]:
    """Prefer paragraph/list units, then sentence boundaries for long paragraphs."""
    units: list[str] = []
    for paragraph in re.split(r'\n\s*\n', text.strip()):
        paragraph = paragraph.strip()
        if not paragraph:
            continue
        if '\n' in paragraph:
            candidates = [line.strip() for line in paragraph.splitlines() if line.strip()]
        else:
            candidates = [piece.strip() for piece in re.split(r'(?<=[.!?])\s+', paragraph) if piece.strip()]
        units.extend(candidates or [paragraph])
    return units


def _split_oversized_unit(title: str, task: str, section: str, unit: str) -> list[str]:
    """Split only an over-limit sentence/line on tokenizer boundaries."""
    tokenizer = _tokenizer()
    ids = tokenizer(unit, add_special_tokens=False, truncation=False, verbose=False)['input_ids']
    parts: list[str] = []
    start = 0
    while start < len(ids):
        low, high, best = start + 1, len(ids), start
        while low <= high:
            middle = (low + high) // 2
            candidate = tokenizer.decode(ids[start:middle], clean_up_tokenization_spaces=False).strip()
            if candidate and token_count(_chunk_text(title, task, section, candidate)) <= MAX_TOKENS:
                best = middle
                low = middle + 1
            else:
                high = middle - 1
        if best == start:
            raise ValueError(f'Chunk metadata exceeds {MAX_TOKENS} tokens: {title} / {section}')
        parts.append(tokenizer.decode(ids[start:best], clean_up_tokenization_spaces=False).strip())
        start = best
    return parts


def _pack_units(title: str, task: str, section: str, units: list[str]) -> list[str]:
    """Pack meaningful units near 90-110 tokens; never exceed the hard 128-token limit."""
    safe_units: list[str] = []
    for unit in units:
        if token_count(_chunk_text(title, task, section, unit)) <= MAX_TOKENS:
            safe_units.append(unit)
        else:
            safe_units.extend(_split_oversized_unit(title, task, section, unit))

    chunks: list[str] = []
    buffer = ''
    for unit in safe_units:
        candidate = f'{buffer}\n\n{unit}'.strip() if buffer else unit
        if token_count(_chunk_text(title, task, section, candidate)) <= TARGET_TOKENS:
            buffer = candidate
            continue
        if buffer:
            chunks.append(buffer)
            buffer = unit
        else:
            chunks.append(unit)
    if buffer:
        chunks.append(buffer)
    return chunks


def _sections(body: str) -> list[tuple[str, str]]:
    headers = list(HEAD.finditer(body))
    if not headers:
        return [('본문', body)] if body.strip() else []
    segments: list[tuple[str, str]] = []
    preamble = body[:headers[0].start()].strip()
    if preamble:
        segments.append(('개요', preamble))
    parent = ''
    for index, header in enumerate(headers):
        level, heading = len(header.group(1)), header.group(2)
        if level == 2:
            parent = heading
        section = f'{parent} > {heading}' if level > 2 and parent and parent != heading else heading
        section_body = body[header.end():headers[index + 1].start() if index + 1 < len(headers) else len(body)].strip()
        if section_body:
            segments.append((section, section_body))
    return segments


def build_chunks(mode: str = 'poc') -> list[dict]:
    if mode not in ('poc', 'production'):
        raise ValueError('RAG_MODE must be poc or production')
    manifest = yaml.safe_load((ROOT / 'knowledge/documents_manifest.yaml').read_text(encoding='utf8'))['documents']
    chunks: list[dict] = []
    for row in manifest:
        if not row['path'].startswith('knowledge/curated/'):
            continue
        if not row['retrieval_enabled_' + ('dev' if mode == 'poc' else 'prod')]:
            continue
        if mode == 'production' and (not row['verified'] or not row['source_verified']):
            continue
        raw = (ROOT / row['path']).read_text(encoding='utf-8-sig')
        front = FRONT.match(raw)
        if not front:
            raise ValueError(f"Missing YAML frontmatter: {row['path']}")
        body = raw[front.end():].strip()
        for section, section_body in _sections(body):
            units = _sentence_units(section_body)
            if not units:
                continue
            for task in row['task_codes']:
                for ordinal, part in enumerate(_pack_units(row['title'], task, section, units)):
                    text = _chunk_text(row['title'], task, section, part)
                    if token_count(text) > MAX_TOKENS:
                        raise AssertionError(f'Overlong chunk: {row["doc_id"]} / {section}')
                    key = f"{row['doc_id']}|{section}|{ordinal}|{task}"
                    chunks.append({
                        'chunk_id': hashlib.sha256(key.encode()).hexdigest()[:24],
                        'doc_id': row['doc_id'], 'task_code': task, 'title': row['title'],
                        'section': section, 'text': text, 'source_file': row['source_file'],
                        'source_pages': row['source_pages'], 'audience': row['audience'],
                        'trust_level': row['trust_level'], 'verified': bool(row['verified']),
                        'source_verified': bool(row['source_verified']),
                    })
    return chunks
