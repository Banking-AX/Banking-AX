"""False-alarm audit of the conflict detector on every approved chunk of each task, plus what real searches return. No LLM."""
from __future__ import annotations

import json
from itertools import combinations

from _common import AI, DATA, prepare, save

prepare(real=False)
from app.core.staff_engine.rag_adapter import keyword_rag_search  # noqa: E402
from app.staff_agent.basis_conflict import find_conflicts  # noqa: E402

index = [row for row in json.loads((AI / '.index' / 'chunks.json').read_text(encoding='utf-8')) if row.get('verified')]
to_row = lambda r: {'docId': r['doc_id'], 'section': r['section'], 'text': r['text']}  # noqa: E731
pairs, flagged = 0, []
for task in sorted({r['task_code'] for r in index}):
    rows = [r for r in index if r['task_code'] == task]
    for left, right in combinations(rows, 2):
        if left['doc_id'] == right['doc_id']:
            continue
        pairs += 1
        for conflict in find_conflicts([to_row(left), to_row(right)]):
            flagged.append({'task': task, 'values': conflict['values'], 'sources': conflict['sources'], 'humanVerdict': None})
questions = json.loads((DATA / 'rag_questions.json').read_text(encoding='utf-8'))['questions']
search_hits = []
queries = [(q['q'], q['task'], q['id']) for q in questions]
queries += [('상속예금 지급 필요서류 불참 상속인 위임장 인감증명서 위임서류', 'INHERITANCE_DEPOSIT', 'BRIEF-delegated'), ('상속예금 지급 필요서류 상속인 방문 서류', 'INHERITANCE_DEPOSIT', 'BRIEF-attending')]
for text, task, qid in queries:
    rows = [to_row(r) for r in keyword_rag_search(text, task, 4, 'staff') if r.get('verified')]
    found = find_conflicts(rows)
    if found:
        search_hits.append({'id': qid, 'q': text, 'conflicts': found})
report = {'mode': '실제 승인 문서에 대한 충돌 감지 점검. LLM 없음', 'chunks': len(index), 'crossDocumentPairs': pairs, 'flagged': len(flagged), 'details': flagged, 'searchesTried': len(queries), 'searchesWithConflict': len(search_hits), 'searchDetails': search_hits}
save('conflict_detector_audit.json', report)
print('chunks', len(index), 'pairs', pairs, 'flagged', len(flagged), 'searches', len(queries), 'withConflict', len(search_hits))
seen = set()
for row in flagged:
    key = tuple(s['excerpt'] for s in row['sources'])
    if key in seen:
        continue
    seen.add(key)
    print(row['task'], row['values'], '|', row['sources'][0]['docId'], row['sources'][0]['excerpt'][:70], '||', row['sources'][1]['docId'], row['sources'][1]['excerpt'][:70])
