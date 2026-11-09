"""Turns results/P0.json and P1.json into summary.json. Pools samples across repetitions and reports each repetition's spread.

p99 is reported only with at least 100 samples, otherwise null. Business rejections are not errors.
"""
from __future__ import annotations

import json
import statistics as st
from collections import Counter, defaultdict
from pathlib import Path

RESULTS = Path(__file__).resolve().parents[1] / 'results'


def nearest(values, q):
    ordered = sorted(values)
    if not ordered:
        return None
    return round(ordered[min(len(ordered) - 1, max(0, int(round(q * len(ordered) + 0.5)) - 1))], 2)


def describe(values):
    if not values:
        return {'n': 0}
    return {'n': len(values), 'p50': nearest(values, .5), 'p95': nearest(values, .95) if len(values) >= 20 else None, 'p99': nearest(values, .99) if len(values) >= 100 else None, 'max': round(max(values), 2)}


def summarize(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[row['scenario']].append(row)
    out = {}
    for name, runs in groups.items():
        ops = {}
        for op in {o for r in runs for o in r['ops']}:
            samples = [m for r in runs for m in r['ops'].get(op, {}).get('ms', [])]
            classes = Counter()
            codes = Counter()
            for r in runs:
                classes.update(r['ops'].get(op, {}).get('classes', {}))
                codes.update(r['ops'].get(op, {}).get('codes', {}))
            total = sum(classes.values())
            ops[op] = {**describe(samples), 'classes': dict(classes), 'okRate': round(classes['ok'] / total, 4) if total else None, 'systemErrorRate': round(classes['system'] / total, 4) if total else None, 'codes': dict(codes)}
        waits = [v for r in runs for v in r['lock']['lockWaitMs']]
        txs = [v for r in runs for v in r['lock']['txMs']]
        out[name] = {
            'reps': len(runs), 'meta': runs[0]['meta'], 'failedRuns': sum(bool(r['failedExpectations']) for r in runs), 'failures': [f for r in runs for f in r['failedExpectations']][:6],
            'peakInFlight': max(r['peakInFlight'] for r in runs), 'durationSecMedian': round(st.median(r['durationSec'] for r in runs), 3), 'ops': ops,
            'lockWaitMs': describe(waits), 'txMs': describe(txs),
            'runMakespanMs': describe([r['durationSec'] * 1000 for r in runs]),
            'deadlocks': sum(r['innodb']['lock_deadlocks'] for r in runs), 'lockTimeouts': sum(r['innodb']['lock_timeouts'] for r in runs),
            'dbTotals': {k: sum(r['db'][k] for r in runs) for k in runs[0]['db']},
            'cpuSecPerRun': {k: round(st.median(r['resources'][k] for r in runs), 3) for k in ('springCpuSec', 'mysqlCpuSec', 'driverCpuSec')},
            'throttledSec': round(sum(r['resources']['springThrottledSec'] + r['resources']['mysqlThrottledSec'] for r in runs), 3),
            'peakMb': {'spring': max(r['resources']['springPeakMb'] for r in runs), 'mysql': max(r['resources']['mysqlPeakMb'] for r in runs)},
        }
    return out


report = {}
for profile in ('P0', 'P1'):
    path = RESULTS / f'{profile}.json'
    if path.exists():
        report[profile] = summarize(json.loads(path.read_text(encoding='utf-8')))
(RESULTS / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')
print('wrote summary', {k: len(v) for k, v in report.items()})
