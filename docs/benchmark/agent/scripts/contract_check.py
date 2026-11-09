"""Spring-FastAPI contract: every path and token header Spring calls exists in the source app; compares the running 9001 read-only."""
from __future__ import annotations

import json
import re
import urllib.request

from _common import REPO, prepare, save

prepare(real=False)
import app.main as main  # noqa: E402

source_paths = sorted(main.app.openapi()['paths'])
java = {p.name: p.read_text(encoding='utf-8') for p in (REPO / 'backend/src/main/java').rglob('*.java') if re.search(r'staff-agent|customer-agent/|staff-ranking|mock-bank', p.read_text(encoding='utf-8'))}
called = sorted({m for text in java.values() for m in re.findall(r'"(/?(?:api/v1/)?(?:staff-agent|customer-agent|staff-ranking|mock-bank)/?[^"?]*)"', text) if '/' in m.strip('/')})
rows = []
for path in called:
    wanted = path if path.startswith('/') else '/' + path
    candidates = [p for p in source_paths if p == wanted or p.endswith(wanted)]
    rows.append({'springPath': path, 'found': bool(candidates), 'matched': candidates[:1]})
headers = {'X-Customer-Agent-Token': any('X-Customer-Agent-Token' in t for t in java.values()), 'X-Staff-Agent-Token': any('X-Staff-Agent-Token' in t for t in java.values())}
try:
    running = sorted(json.load(urllib.request.urlopen('http://127.0.0.1:9001/openapi.json', timeout=8))['paths'])
except Exception as failure:  # noqa: BLE001
    running = None
    print('9001 unreadable', type(failure).__name__)
public = sorted(p for p in source_paths if p.startswith('/api') or p.startswith('/mock-bank') or p == '/health')
report = {'springCalls': rows, 'allFound': all(r['found'] for r in rows), 'tokenHeadersUsedBySpring': headers, 'running9001SameAsSource': None if running is None else sorted(running) == public,
          'onlyInSource': [] if running is None else sorted(set(public) - set(running)), 'onlyIn9001': [] if running is None else sorted(set(running) - set(public))}
save('contract_check.json', report)
print(json.dumps(report, ensure_ascii=False, indent=1)[:2200])
