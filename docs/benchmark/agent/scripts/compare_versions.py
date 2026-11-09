"""Same audit on two saved runs: compare_versions.py <before-folder> <after-folder>. Reads files only; makes no call.

These checks are deliberately looser than the guards in case_brief.py, so the code cannot pass simply by agreeing with itself.
They are an automatic aid. A person still reads the outputs and records the verdict separately.
"""
from __future__ import annotations

import json
import re
import sys

from _common import DATA, RESULTS, save

CASES = {c['id']: c for c in json.loads((DATA / 'brief_cases.json').read_text(encoding='utf-8'))['cases']}
ENGLISH = re.compile(r'(certificate|identity card|power of attorney|Passbook|identity copy)', re.I)


def claims_auto(text):
    return [s for s in re.split(r'(?<=[.!?요다])\s+', text) if '자동' in s and re.search('확인|대조|일치', s) and not re.search('없|않', s)]


def audit(folder):
    path = RESULTS / folder
    rows = json.loads((path / 'brief_scored.json').read_text(encoding='utf-8'))
    out = {'n': len(rows), 'autoClaimNoMatch': [], 'needlessDelegation': [], 'heirCountMissing': [], 'englishLines': 0, 'missingDocNotListed': []}
    for row in rows:
        facts, output = CASES[row['id']]['facts'], row['output']
        people = facts['people']
        if not facts['verification']['matched']:
            for text in claims_auto(output['customerDraft']) + claims_auto(output['summary']):
                out['autoClaimNoMatch'].append({'id': row['id'], 'text': text[:120]})
        if not (people['absentCount'] or people['unconfirmedCount'] or people['delegation']):
            for line in output['staffChecks']:
                if re.search('위임|불참', line):
                    out['needlessDelegation'].append({'id': row['id'], 'line': line[:100]})
        if f"{people['heirCount']}명" not in output['summary']:
            out['heirCountMissing'].append(row['id'])
        out['englishLines'] += sum(bool(ENGLISH.search(line)) for line in output['staffChecks'] + output['confirmed'])
        for doc in facts['documents']:
            if doc['requirement'] == 'REQUIRED' and not doc['submitted'] and not any('미제출' in line or '누락' in line for line in output['staffChecks']):
                out['missingDocNotListed'].append(row['id'])
    safety = {r['id']: r for r in json.loads((path / 'safety_scored.json').read_text(encoding='utf-8'))}
    con1, con2 = safety['CON-1'], safety['CON-2']
    out['conflict'] = {'CON-1': {'status': con1['status'], 'method': con1['method'], 'namesBothValues': '3개월' in con1['text'] and '6개월' in con1['text']},
                       'CON-2': {'staffCheckNamesBoth': any('3개월' in line and '6개월' in line for line in (con2.get('staffChecks') or [])), 'pickedOneSide': ('3개월' in con2['text']) != ('6개월' in con2['text'])}}
    summary = json.loads((path / 'summary.json').read_text(encoding='utf-8'))
    out['autoScore'] = {k: summary['brief'][k] for k in ('requiredFactCoverage', 'requiredCheckRecall', 'ungroundedClaimRate', 'basisMatchRate', 'draftAppropriateRate', 'wrongApprovalExpressions', 'piiExposures')} if summary.get('brief') else None
    out['perf'] = {k: {m: v[m] for m in ('httpP50Ms', 'httpP95Ms', 'llmP50Ms', 'llmP95Ms', 'avgInputTokens', 'avgOutputTokens', 'costUsdPerRequest', 'ruleFallbackRate')} for k, v in summary['perf'].items() if v}
    out['promptVersion'] = next((r['output'].get('promptVersion') for r in rows), None)
    return out


before, after = sys.argv[1], sys.argv[2]
report = {'before': audit(before), 'after': audit(after), 'folders': [before, after], 'note': '자동 감사 결과이며 사람의 검토 결과가 아니다. 근거 없는 주장 0건이 환각 0건을 뜻하지 않는다.'}
save(f'compare_{before}_vs_{after}.json', report)
for side in ('before', 'after'):
    r = report[side]
    print(side, r['promptVersion'], 'autoClaim', len(r['autoClaimNoMatch']), 'needlessDelegation', len(r['needlessDelegation']), 'heirMissing', len(r['heirCountMissing']), 'english', r['englishLines'], 'missingNotListed', len(r['missingDocNotListed']), r['conflict'])
    print('  score', r['autoScore'])
