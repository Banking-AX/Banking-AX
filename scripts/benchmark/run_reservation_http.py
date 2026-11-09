"""One isolated 20-request reservation race against the internal Spring HTTP API.

Requires BENCH_BASE_URL and refuses the shared development ports.  The SQL fixture
must be loaded into banking_ax_bench immediately before each invocation.
"""
from __future__ import annotations
import json, os, statistics, sys, threading, time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

base = os.environ.get('BENCH_BASE_URL', '')
if not base or any(f':{port}' in base for port in ('18081', '9001', '5175')):
    raise SystemExit('BENCH_BASE_URL must be a non-shared isolated endpoint.')
token = os.environ.get('BENCH_GATEWAY_TOKEN', 'bench-token')
slot = os.environ.get('BENCH_SLOT_START', '2030-01-02T09:00:00+09:00')
out = Path(__file__).resolve().parents[2] / 'docs/benchmark/results'
out.mkdir(parents=True, exist_ok=True)
gate = threading.Barrier(20)

def one(n: int) -> dict:
    body = json.dumps({'requestId': f'http-race-{n}', 'caseId': 7000+n, 'branchId': 1, 'startsAt': slot}).encode()
    request = Request(base + '/api/v1/reservations', body, method='POST', headers={'Content-Type': 'application/json', 'X-Actor-Id': str(1000+n), 'X-Actor-Role': 'CUSTOMER', 'X-Tool-Gateway-Token': token})
    gate.wait(); started = time.perf_counter()
    try:
        with urlopen(request, timeout=15) as response: payload, status = response.read().decode(), response.status
    except HTTPError as error: payload, status = error.read().decode(), error.code
    except Exception as error: payload, status = str(error), 0
    return {'caseId': 7000+n, 'status': status, 'ms': round((time.perf_counter()-started)*1000, 3), 'body': payload[:1000]}

with ThreadPoolExecutor(max_workers=20) as pool:
    records = list(pool.map(one, range(1, 21)))
latencies = sorted(row['ms'] for row in records)
summary = {'requests': 20, 'http2xx': sum(200 <= x['status'] < 300 for x in records), 'http409': sum(x['status'] == 409 for x in records), 'http5xx': sum(x['status'] >= 500 for x in records), 'transportErrors': sum(x['status'] == 0 for x in records), 'p50Ms': latencies[9], 'p95Ms': latencies[18], 'throughputRequestsPerSecond': round(20 / (max(x['ms'] for x in records)/1000), 3), 'records': records}
target = out / 'reservation_http_latest.json'; target.write_text(json.dumps(summary, indent=2), encoding='utf-8')
print(json.dumps(summary, indent=2))
