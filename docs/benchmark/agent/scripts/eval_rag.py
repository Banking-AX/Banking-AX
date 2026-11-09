"""RAG retrieval quality, no LLM and no network (local embedding model, HF offline). Retrieval only; answer quality is separate."""
from __future__ import annotations

import json
import time

from _common import AI, DATA, percentile, prepare, save

prepare(real=False)
import shutil  # noqa: E402
import tempfile  # noqa: E402
from pathlib import Path  # noqa: E402

import app.retrieve as retrieve  # noqa: E402
from app.retrieve import search_with_strategy  # noqa: E402

# Chroma may touch its files when opened, and the live 9001 uses this directory, so only a copy is opened.
_copy = Path(tempfile.mkdtemp(prefix='agent-eval-chroma-')) / 'vector'
shutil.copytree(AI / '.vector_db', _copy)
retrieve.VECTOR = _copy

raw = json.loads((DATA / 'rag_questions.json').read_text(encoding='utf-8'))
NEVER = set(raw['never_retrieve'])
index = json.loads((AI / '.index' / 'chunks.json').read_text(encoding='utf-8'))
manifest_lines = (AI / 'knowledge' / 'documents_manifest.csv').read_text(encoding='utf-8-sig').splitlines()
has_version = any('version' in key.lower() for row in index[:1] for key in row) or 'version' in manifest_lines[0].lower()


def hit(row, q):
    return row['doc_id'] in q['docs'] and any(s in row['section'] for s in q['sections'])


def evaluate(strategy):
    details, latency = [], []
    for q in raw['questions']:
        audience = q.get('audience', 'staff' if q.get('type') != 'audience_guard' else 'customer')
        started = time.perf_counter()
        rows = search_with_strategy(q['q'], q['task'], 5, audience, strategy)
        latency.append((time.perf_counter() - started) * 1000)
        kind = q.get('type', 'answerable')
        record = {'id': q['id'], 'type': kind, 'task': q['task'], 'q': q['q'], 'audience': audience, 'top5': [[r['doc_id'], r['section'][:50]] for r in rows],
                  'neverRetrieved': [r['doc_id'] for r in rows if r['doc_id'] in NEVER or not r.get('verified')], 'humanVerdict': None}
        if kind == 'answerable':
            ranks = [i for i, r in enumerate(rows, 1) if hit(r, q)]
            record.update(firstRank=ranks[0] if ranks else None, recall3=bool(ranks and ranks[0] <= 3), recall5=bool(ranks and ranks[0] <= 5),
                          irrelevantTop3=sum(r['doc_id'] not in q['docs'] for r in rows[:3]), top3=min(3, len(rows)))
        elif kind == 'audience_guard':
            record['leaked'] = [r['doc_id'] for r in rows if r['doc_id'] in q['forbidden_docs']]
        else:
            record['returned'] = len(rows)
        details.append(record)
    answerable = [d for d in details if d['type'] == 'answerable']
    guard = [d for d in details if d['type'] == 'audience_guard']
    none = [d for d in details if d['type'] == 'unanswerable']
    return {'strategy': strategy, 'answerableN': len(answerable), 'recallAt3': round(sum(d['recall3'] for d in answerable) / len(answerable), 4), 'recallAt5': round(sum(d['recall5'] for d in answerable) / len(answerable), 4),
            'mrr': round(sum(1 / d['firstRank'] for d in answerable if d['firstRank']) / len(answerable), 4), 'irrelevantTop3Rate': round(sum(d['irrelevantTop3'] for d in answerable) / max(1, sum(d['top3'] for d in answerable)), 4),
            'unapprovedOrUnverifiedRetrieved': sum(len(d['neverRetrieved']) for d in details), 'audienceLeaks': sum(len(d['leaked']) for d in guard), 'audienceGuardN': len(guard),
            'unanswerableN': len(none), 'unanswerableReturnedResults': sum(d['returned'] > 0 for d in none), 'latencyP50Ms': percentile(latency, .5), 'latencyP95Ms': percentile(latency, .95),
            'misses': [{'id': d['id'], 'q': d['q'], 'firstRank': d['firstRank'], 'top3': d['top5'][:3]} for d in answerable if not d['recall3']]}, details


report = {'mode': 'REAL retrieval, no LLM', 'indexChunks': len(index), 'documentVersionFieldPresent': has_version, 'indexDocIds': sorted({r['doc_id'] for r in index}),
          'manifestMarkedProdEnabled': sum(line.rstrip().endswith('True') for line in manifest_lines[1:]), 'manifestRows': len(manifest_lines) - 1}
all_details = {}
for name in ('keyword', 'hybrid'):
    summary, details = evaluate(name)
    report[name] = summary
    all_details[name] = details
save('rag_summary.json', report)
save('rag_details.json', all_details)
print(json.dumps(report, ensure_ascii=False, indent=1)[:4500])
