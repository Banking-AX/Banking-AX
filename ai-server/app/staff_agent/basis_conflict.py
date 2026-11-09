"""Finds verified policy passages that give different values for the same rule, without asking the model.

A conflict is settled only when both sides carry an approved version and a date already in effect; the later one applies.
Everything else stays UNRESOLVED and goes to staff with both sources. The current index has no version or date fields, so
today every real conflict is UNRESOLVED. No source-priority order is defined for the bank, so none is guessed here.
"""
from __future__ import annotations

import re
from datetime import date
from difflib import SequenceMatcher

VALUE = re.compile(r'\d[\d,.]*\s*(?:개월|영업일|년|일|회|%|명|[천백십]?만\s*원|원)')
MIN_SIMILARITY = 0.85
MIN_LENGTH = 8
EXCERPT_LIMIT = 80


def _sentences(text: str) -> list[str]:
    return [part.strip() for part in re.split(r'(?<=[.!?])\s+|\n+', text) if part.strip()]


def _values(sentence: str) -> list[str]:
    return [re.sub(r'\s+', '', found) for found in VALUE.findall(sentence)]


def _shape(sentence: str) -> str:
    """The sentence with its values removed, so two statements of one rule compare equal."""
    return re.sub(r'[^가-힣a-zA-Z#]', '', VALUE.sub('#', sentence))


def _comparable(left: dict, right: dict) -> bool:
    """Two statements inside one document are usually tiers of a rule, unless the document is versioned."""
    if left['docId'] != right['docId']:
        return True
    return bool(left.get('approvedVersion') and right.get('approvedVersion') and left['approvedVersion'] != right['approvedVersion'])


def _effective(row: dict) -> date | None:
    if not row.get('approvedVersion') or not row.get('effectiveAt'):
        return None
    try:
        when = date.fromisoformat(str(row['effectiveAt'])[:10])
    except ValueError:
        return None
    return when if when <= date.today() else None


def _settled(left: dict, right: dict) -> str | None:
    first, second = _effective(left), _effective(right)
    if first is None or second is None or first == second:
        return None
    return left['docId'] if first > second else right['docId']


def _source(row: dict, sentence: str, value: str) -> dict:
    return {'docId': row['docId'], 'section': row['section'], 'value': value, 'excerpt': sentence[:EXCERPT_LIMIT]}


def find_conflicts(evidence: list[dict]) -> list[dict]:
    found, seen = [], set()
    for index, left in enumerate(evidence):
        for right in evidence[index + 1:]:
            if not _comparable(left, right):
                continue
            for one in _sentences(left['text']):
                for other in _sentences(right['text']):
                    first, second = _values(one), _values(other)
                    if not first or len(first) != len(second) or sorted(first) == sorted(second):
                        continue
                    if len(_shape(one)) < MIN_LENGTH or SequenceMatcher(None, _shape(one), _shape(other)).ratio() < MIN_SIMILARITY:
                        continue
                    a, b = next((pair for pair in zip(first, second) if pair[0] != pair[1]), (first[0], second[0]))
                    key = (left['docId'], right['docId'], a, b)
                    if key in seen:
                        continue
                    seen.add(key)
                    applied = _settled(left, right)
                    found.append({'status': 'RESOLVED' if applied else 'UNRESOLVED', 'applied': applied, 'values': [a, b],
                                  'sources': [_source(left, one, a), _source(right, other, b)]})
    return found


def usable_evidence(evidence: list[dict], conflicts: list[dict]) -> list[dict]:
    """Drops only the side a settled conflict replaced; an unresolved conflict keeps both so staff can see them."""
    replaced = {source['docId'] for row in conflicts if row['status'] == 'RESOLVED' for source in row['sources'] if source['docId'] != row['applied']}
    return [row for row in evidence if row['docId'] not in replaced]


def unresolved(conflicts: list[dict]) -> list[dict]:
    return [row for row in conflicts if row['status'] == 'UNRESOLVED']


def describe(conflict: dict) -> str:
    return ' / '.join(f"{source['docId']} {source['section']}: {source['value']}" for source in conflict['sources'])
