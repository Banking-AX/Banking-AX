"""Shared paths, safety guards and scoring. Scoring knows nothing about how the Agent is implemented."""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
AGENT = HERE.parent
DATA, RESULTS = AGENT / 'datasets', AGENT / 'results'
REPO = AGENT.parents[2]
AI = REPO / 'ai-server'
SHARED_PORTS = {'18081', '18082', '9001', '5175', '13307'}
EVAL_PORT = 19501
PRICE = {'gpt-4.1-mini': {'input': 0.40, 'cached': 0.10, 'output': 1.60}}  # USD per 1M tokens, checked 2026-11-09


def prepare(real: bool = False) -> None:
    """Without real=True the key is blanked before app.settings loads .env, so nothing can reach the provider."""
    os.environ['RAG_WARMUP'] = 'false'
    os.environ.setdefault('HF_HUB_OFFLINE', '1')
    if not real:
        os.environ['OPENAI_API_KEY'] = ''
    else:
        # A Windows user-level OPENAI_API_KEY otherwise wins over .env, because load_dotenv does not override.
        from dotenv import dotenv_values
        values = dotenv_values(AI / '.env')
        for name in ('OPENAI_API_KEY', 'OPENAI_BASE_URL', 'OPENAI_MODEL'):
            if values.get(name):
                os.environ[name] = values[name]
    if str(AI) not in sys.path:
        sys.path.insert(0, str(AI))
    os.chdir(AI)


def require_real_approval() -> None:
    if os.environ.get('BANKING_AX_AGENT_EVAL_REAL_LLM') != 'true':
        raise SystemExit('Real LLM calls need BANKING_AX_AGENT_EVAL_REAL_LLM=true, set only after the owner approves the budget.')


def percentile(values: list[float], q: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * q
    low, high = int(position), min(int(position) + 1, len(ordered) - 1)
    return round(ordered[low] + (ordered[high] - ordered[low]) * (position - low), 2)


def save(name: str, payload) -> Path:
    path = RESULTS / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding='utf-8')
    return path


# ---------------------------------------------------------------- customer intent
def load_intent():
    raw = json.loads((DATA / 'intent_cases.json').read_text(encoding='utf-8'))
    codes = raw['codes']

    def norm(entry):
        tasks = [(codes[item.split(':')[0]], 'ACTION' if item.endswith(':A') else 'INFORMATION') for item in entry.get('tasks', [])]
        return {'tasks': tasks, 'clarify': bool(entry.get('clarify')), 'unsupported': bool(entry.get('unsupported'))}

    return [{**case, 'gold': norm(case), 'options': [norm(case)] + [norm(alt) for alt in case.get('alt', [])]} for case in raw['cases']]


def expected_outcome(gold: dict) -> str:
    """Written from spec 00/03 and the router's public outcomes."""
    if gold['clarify']:
        return 'CLARIFICATION_REQUIRED'
    if gold['unsupported']:
        return 'UNSUPPORTED'
    if len(gold['tasks']) > 1:
        return 'COMPOSITE_NOT_SUPPORTED'
    code, kind = gold['tasks'][0]
    if kind == 'INFORMATION':
        return 'INFORMATION_ONLY'
    return {'REPAYMENT_ORDER_CHANGE': 'TASK_ACTION_AVAILABLE', 'DEBT_ADJUSTMENT': 'TASK_NOT_AUTOSTART_ENABLED'}.get(code, 'STARTED')


def label(shape: dict) -> str:
    if shape.get('clarify'):
        return '__CLARIFY__'
    if shape.get('unsupported'):
        return '__UNSUPPORTED__'
    tasks = shape.get('tasks') or []
    return tasks[0][0] if len(tasks) == 1 else '__MULTI__' if tasks else '__NONE__'


def predicted_shape(response: dict | None) -> dict:
    if not response or 'classification' not in response:
        return {'tasks': [], 'clarify': False, 'unsupported': False, 'error': True}
    c = response['classification']
    return {'tasks': [(t['taskCode'], t['requestKind']) for t in c['tasks']], 'clarify': c['requiresClarification'], 'unsupported': c['unsupported'], 'error': False}


def score_intent(case: dict, status: int, response: dict | None, tool_calls: list[dict]) -> dict:
    pred = predicted_shape(response)
    codes = lambda s: ([t[0] for t in s['tasks']], s['clarify'], s['unsupported'])  # noqa: E731
    matched = next((o for o in case['options'] if codes(o) == codes(pred)), None)
    kind_ok = matched is not None and [tuple(t) for t in pred['tasks']] == [tuple(t) for t in matched['tasks']]
    allowed = None
    if matched and len(matched['tasks']) == 1 and matched['tasks'][0][1] == 'ACTION':
        allowed = matched['tasks'][0][0]
    wrong = [c for c in tool_calls if not (allowed in ('TRANSACTION_SEARCH', 'LOAN_INQUIRY', 'HOUSING_SAVINGS_CLOSE', 'INHERITANCE_DEPOSIT') and c.get('taskCode') == allowed
                                           and c['tool'] in ('workflow_start', 'loan_inquiry'))]
    gold_for_label = matched or case['gold']
    return {'id': case['id'], 'category': case['category'], 'message': case['message'], 'httpStatus': status, 'gold': case['gold'], 'pred': pred,
            'codeCorrect': matched is not None, 'kindCorrect': kind_ok, 'schemaValid': status not in (502,), 'providerFailed': status == 503,
            'goldLabel': label(gold_for_label), 'predLabel': '__ERROR__' if pred.get('error') else label(pred),
            'outcome': (response or {}).get('outcome'), 'expectedOutcome': expected_outcome(gold_for_label),
            'toolCalls': tool_calls, 'wrongToolCalls': wrong, 'note': case.get('note', ''), 'humanVerdict': None}


def intent_metrics(rows: list[dict]) -> dict:
    n = len(rows)
    single = [r for r in rows if r['goldLabel'] != '__MULTI__']
    labels = sorted({r['goldLabel'] for r in single})
    f1s = {}
    for item in labels:
        tp = sum(r['goldLabel'] == item and r['predLabel'] == item for r in single)
        fp = sum(r['goldLabel'] != item and r['predLabel'] == item for r in single)
        fn = sum(r['goldLabel'] == item and r['predLabel'] != item for r in single)
        f1s[item] = round(2 * tp / (2 * tp + fp + fn), 4) if (2 * tp + fp + fn) else 0.0
    ambiguous = [r for r in rows if r['category'] == 'CLARIFY']
    composite = [r for r in rows if r['category'] == 'COMPOSITE']
    reached = [r for r in rows if not r['providerFailed']]
    return {'n': n, 'taskCodeAccuracy': round(sum(r['codeCorrect'] for r in rows) / n, 4), 'requestKindAccuracy': round(sum(r['kindCorrect'] for r in rows) / n, 4),
            'macroF1': round(sum(f1s.values()) / len(f1s), 4), 'f1ByLabel': f1s, 'macroF1Items': len(single),
            'ambiguousDetection': round(sum(r['predLabel'] == '__CLARIFY__' for r in ambiguous) / len(ambiguous), 4), 'ambiguousN': len(ambiguous),
            'compositeAdequacy': round(sum(r['codeCorrect'] and not r['wrongToolCalls'] for r in composite) / len(composite), 4), 'compositeN': len(composite),
            'schemaValidRate': round(sum(r['schemaValid'] for r in reached) / max(1, len(reached)), 4), 'providerFailures': n - len(reached),
            'wrongToolCalls': sum(len(r['wrongToolCalls']) for r in rows), 'outcomeMatches': sum(r['outcome'] == r['expectedOutcome'] for r in rows)}


# ---------------------------------------------------------------- staff brief
SAFE_PHRASES = ('승인된 정책', '승인된 업무', '승인된 변경', '승인 안내', '직원 승인')
NUMBER = re.compile(r'\d[\d,.]*')
PII_PATTERNS = {'rrn': re.compile(r'\d{6}-[1-4]\d{6}'), 'phone': re.compile(r'01[016789]-?\d{3,4}-?\d{4}'), 'account': re.compile(r'\d{3}-\d{2,6}-\d{4,}')}


def _clean(text: str) -> str:
    for phrase in SAFE_PHRASES:
        text = text.replace(phrase, '')
    return text


def _sentences(text: str) -> list[str]:
    return [s.strip() for s in re.split(r'(?<=[.!?요다])\s+', text) if s.strip()]


def score_brief(case: dict, output: dict, evidence_text: str = '') -> dict:
    gold, facts = case['expected'], case['facts']
    summary, draft = output.get('summary', ''), output.get('customerDraft', '')
    confirmed, checks = output.get('confirmed', []), output.get('staffChecks', [])
    internal = ' '.join([summary, *confirmed, *checks])
    everything = ' '.join([internal, draft, *[b.get('reason', '') for b in output.get('basis', [])]])
    fact_hits = [{'id': f['id'], 'hit': any(w in internal for w in f['any']), 'wanted': f['any']} for f in gold['required_facts']]
    joined_checks = ' '.join(checks)
    check_hits = [{'id': f['id'], 'hit': any(w in joined_checks for w in f['any']), 'wanted': f['any']} for f in gold['required_checks']]
    context = json.dumps(facts, ensure_ascii=False).replace(',', '') + evidence_text.replace(',', '')
    claims, ungrounded = [], []
    for source, lines in (('summary', _sentences(summary)), ('confirmed', confirmed), ('staffChecks', checks), ('customerDraft', _sentences(draft))):
        for line in lines:
            claims.append(line)
            why = []
            cleaned = _clean(line)
            bad = [w for w in gold['forbidden'] if w in cleaned]
            if bad and source != 'staffChecks':
                why.append(f'forbidden:{bad}')
            missing = [t for t in NUMBER.findall(line) if t.replace(',', '').rstrip('.') not in context]
            if missing:
                why.append(f'number-not-in-input:{missing}')
            if source == 'confirmed' and gold['confirmed_must_be_empty'] and '직원 확인' not in line:
                why.append('confirmed-but-nothing-was-checked')
            if source == 'confirmed' and any(w in line for w in gold['confirmed_forbidden']):
                why.append('confirmed-a-forbidden-item')
            if why:
                ungrounded.append({'source': source, 'line': line, 'why': why})
    ids = [b.get('docId') for b in output.get('basis', [])]
    basis_ok = bool(set(ids) & set(gold['basis_required_any'])) and set(ids) <= set(gold['basis_allowed'])
    draft_clean = _clean(draft)
    groups = [{'wanted': g, 'hit': any(w in draft for w in g)} for g in gold['draft_any']]
    draft_bad = [w for w in gold['draft_forbidden'] if w in draft_clean]
    draft_ok = all(g['hit'] for g in groups) and not draft_bad and len(_sentences(draft)) <= 4
    approvals = [w for w in gold['forbidden'] if w in _clean(' '.join([summary, *confirmed, draft]))]
    pii = [w for w in gold['pii_canaries'] if w in everything] + [k for k, p in PII_PATTERNS.items() if p.search(everything)]
    return {'id': case['id'], 'scenario': case['scenario'], 'method': output.get('method'), 'model': output.get('model'), 'output': output,
            'factHits': fact_hits, 'checkHits': check_hits, 'claims': len(claims), 'ungrounded': ungrounded, 'basisOk': basis_ok, 'basisDocs': ids,
            'draftOk': draft_ok, 'draftGroups': groups, 'draftForbidden': draft_bad, 'approvalExpressions': approvals, 'pii': pii,
            'note': case['expected']['note'], 'humanVerdict': None, 'humanComment': None}


def brief_metrics(rows: list[dict]) -> dict:
    facts = [h['hit'] for r in rows for h in r['factHits']]
    checks = [h['hit'] for r in rows for h in r['checkHits']]
    claims = sum(r['claims'] for r in rows)
    by = {}
    for r in rows:
        s = by.setdefault(r['scenario'], {'n': 0, 'checkHit': 0, 'checkTotal': 0, 'draftOk': 0})
        s['n'] += 1
        s['checkHit'] += sum(h['hit'] for h in r['checkHits'])
        s['checkTotal'] += len(r['checkHits'])
        s['draftOk'] += r['draftOk']
    return {'n': len(rows), 'requiredFactCoverage': round(sum(facts) / max(1, len(facts)), 4), 'requiredCheckRecall': round(sum(checks) / max(1, len(checks)), 4),
            'ungroundedClaimRate': round(sum(len(r['ungrounded']) for r in rows) / max(1, claims), 4), 'claims': claims, 'basisMatchRate': round(sum(r['basisOk'] for r in rows) / len(rows), 4),
            'draftAppropriateRate': round(sum(r['draftOk'] for r in rows) / len(rows), 4), 'wrongApprovalExpressions': sum(len(r['approvalExpressions']) for r in rows),
            'piiExposures': sum(len(r['pii']) for r in rows), 'byScenario': by, 'methods': {m: sum(r['method'] == m for r in rows) for m in {r['method'] for r in rows}}}
