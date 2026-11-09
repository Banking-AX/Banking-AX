"""The no-LLM floor of the staff opinion: what a staff member gets when the model is unavailable. Free, and not an LLM score."""
from __future__ import annotations

import json

from _common import DATA, brief_metrics, prepare, save, score_brief

prepare(real=False)
from app.staff_agent.case_brief import write_brief  # noqa: E402

cases = json.loads((DATA / 'brief_cases.json').read_text(encoding='utf-8'))['cases']
rows = []
for case in cases:
    output = write_brief(case['facts'], llm=None)
    rows.append(score_brief(case, output, ' '.join(e.get('title', '') + e.get('section', '') for e in output.get('evidence', []))))
metrics = brief_metrics(rows)
assert set(metrics['methods']) == {'RULE_FALLBACK'}
from app.staff_agent.case_brief import PROMPT_VERSION  # noqa: E402

# Each version keeps its own file. brief_rule_baseline.json is the case-brief-5 measurement.
save(f'brief_rule_baseline_{PROMPT_VERSION}.json', {'mode': 'RULE_FALLBACK 경로의 실측. LLM 품질 수치가 아니다', 'metrics': metrics, 'rows': rows})
print(json.dumps(metrics, ensure_ascii=False, indent=1)[:1800])
print('sample staffChecks:', rows[20]['id'], rows[20]['output']['staffChecks'][:3])
print('sample draft:', rows[20]['output']['customerDraft'])
print('misses:', [(r['id'], [h['id'] for h in r['checkHits'] if not h['hit']]) for r in rows if not all(h['hit'] for h in r['checkHits'])][:12])
