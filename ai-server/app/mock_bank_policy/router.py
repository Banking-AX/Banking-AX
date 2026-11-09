"""HTTP feed of a fictional bank's change events. Mounted here for the PoC; it shares nothing with the Agent code."""
from __future__ import annotations

import copy
import hashlib
import hmac
import json
import os
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Query, Response

SOURCE_SYSTEM = 'MOCK_BANK_POLICY'
SCENARIOS = json.loads((Path(__file__).parent / 'scenarios.json').read_text(encoding='utf-8'))


class PolicyFeed:
    """In-memory, append-only feed. Sequence numbers follow the clock so a Mock restart never reuses a cursor."""

    def __init__(self) -> None:
        self._events: list[dict] = []
        self._lock = threading.Lock()

    def publish(self, scenario: str, tag: str = '') -> list[dict]:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        return [self._append(_rendered(event, now, tag)) for event in copy.deepcopy(SCENARIOS[scenario])]

    def replay_last(self) -> dict | None:
        """A retransmission: the identical event body under a new feed position."""
        with self._lock:
            last = copy.deepcopy(self._events[-1]) if self._events else None
        return self._append(last) if last else None

    def _append(self, event: dict) -> dict:
        with self._lock:
            floor = self._events[-1]['seq'] + 1 if self._events else 0
            event['seq'] = max(floor, time.time_ns() // 1_000_000)
            self._events.append(event)
            return event

    def after(self, cursor: int, limit: int) -> tuple[list[dict], int]:
        with self._lock:
            rows = [event for event in self._events if event['seq'] > cursor][:limit]
        return rows, rows[-1]['seq'] if rows else cursor


def _rendered(event: dict, now: datetime, tag: str = '') -> dict:
    """A tag keeps rehearsal ids apart, because Banking-AX permanently remembers every event id it has received."""
    days = event.pop('effectiveInDays')
    if tag:
        event['changeEventId'] = f"{event['changeEventId']}-{tag}"
        event['externalPolicyId'] = f"{event['externalPolicyId']}-{tag}"
    event.update(sourceSystem=SOURCE_SYSTEM, publishedAt=now.isoformat().replace('+00:00', 'Z'),
                 effectiveAt=(now + timedelta(days=days)).isoformat().replace('+00:00', 'Z'))
    return event


def create_router(feed: PolicyFeed | None = None) -> APIRouter:
    router = APIRouter(prefix='/mock-bank/v1', tags=['mock-bank-policy'])
    store = feed or PolicyFeed()

    def require_token(token: str | None) -> None:
        expected = os.getenv('POLICY_SOURCE_TOKEN', '')
        if not expected or not hmac.compare_digest(token or '', expected):
            raise HTTPException(403, 'POLICY_SOURCE_FORBIDDEN')

    @router.get('/policy-changes')
    def changes(after: int = Query(0, ge=0), limit: int = Query(50, ge=1, le=200),
                x_policy_source_token: str | None = Header(default=None)) -> Response:
        require_token(x_policy_source_token)
        secret = os.getenv('POLICY_SOURCE_SIGNING_SECRET', '')
        if not secret:
            raise HTTPException(503, 'POLICY_SOURCE_SIGNING_NOT_CONFIGURED')
        events, cursor = store.after(after, limit)
        body = json.dumps({'sourceSystem': SOURCE_SYSTEM, 'nextCursor': str(cursor), 'events': events},
                          ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        signature = hmac.new(secret.encode('utf-8'), body, hashlib.sha256).hexdigest()
        return Response(body, media_type='application/json', headers={'X-Policy-Signature': f'sha256={signature}'})

    @router.post('/admin/scenarios/{scenario}')
    def publish(scenario: str, tag: str = Query('', pattern='^[A-Z0-9]{0,8}$'),
                x_policy_source_token: str | None = Header(default=None)) -> dict:
        require_token(x_policy_source_token)
        if scenario not in SCENARIOS:
            raise HTTPException(404, 'SCENARIO_NOT_FOUND')
        return {'published': [event['changeEventId'] for event in store.publish(scenario, tag)]}

    @router.post('/admin/replay-last')
    def replay(x_policy_source_token: str | None = Header(default=None)) -> dict:
        require_token(x_policy_source_token)
        event = store.replay_last()
        if event is None:
            raise HTTPException(404, 'NOTHING_TO_REPLAY')
        return {'published': [event['changeEventId']]}

    return router
