"""Two free checks: (1) the scorers tell a perfect answer from a bad one; (2) worst-case cost of the real run, from the actual prompts.

No tokenizer is installed, so tokens are over-estimated as UTF-8 bytes / 2.0. A real-run budget is enforced from provider usage anyway.
"""
from __future__ import annotations

import json

from _common import DATA, PRICE, brief_metrics, intent_metrics, load_intent, prepare, save, score_brief, score_intent

prepare(real=False)
from app.customer_agent.intent_classifier import LlmIntentOutput, SYSTEM_PROMPT as INTENT  # noqa: E402
from app.staff_agent.case_brief import Brief, SYSTEM_PROMPT as BRIEF  # noqa: E402
from app.staff_agent.policy_impact import Interpretation, SYSTEM_PROMPT as IMPACT  # noqa: E402

intents = load_intent()
briefs = json.loads((DATA / 'brief_cases.json').read_text(encoding='utf-8'))['cases']


def response_for(gold):
    return {'outcome': None, 'classification': {'tasks': [{'taskCode': c, 'requestKind': k} for c, k in gold['tasks']], 'requiresClarification': gold['clarify'], 'unsupported': gold['unsupported']}}


perfect = [score_intent(c, 200, response_for(c['gold']), []) for c in intents]
wrong = [score_intent(c, 200, {'classification': {'tasks': [{'taskCode': 'LOAN_INQUIRY', 'requestKind': 'ACTION'}], 'requiresClarification': False, 'unsupported': False}}, [{'tool': 'workflow_start', 'taskCode': 'LOAN_INQUIRY'}]) for c in intents]
good = []
for case in briefs:
    gold = case['expected']
    good.append(score_brief(case, {'summary': ' '.join(f['any'][0] for f in gold['required_facts']) + ' 상황입니다.', 'confirmed': [], 'staffChecks': [f['any'][0] + ' 직접 확인' for f in gold['required_checks']],
                                   'basis': [{'docId': 'C02-01', 'reason': 'x'}], 'customerDraft': ' '.join(g[0] for g in gold['draft_any']) + ' 안내드려요.', 'method': 'LLM'}))
bad = [score_brief(case, {'summary': '승인되었습니다. 지급 가능합니다. 김민수 고객 900101', 'confirmed': ['인감 검증 완료'], 'staffChecks': [], 'basis': [{'docId': 'MADE-UP', 'reason': 'x'}], 'customerDraft': '지급 가능합니다.', 'method': 'LLM'}) for case in briefs]
selftest = {'note': '채점기 검증용 값이며 모델 성능이 아니다',
            'intentPerfect': {k: v for k, v in intent_metrics(perfect).items() if k != 'f1ByLabel'}, 'intentWrong': {k: v for k, v in intent_metrics(wrong).items() if k != 'f1ByLabel'},
            'briefPerfect': {k: v for k, v in brief_metrics(good).items() if k != 'byScenario'}, 'briefBad': {k: v for k, v in brief_metrics(bad).items() if k != 'byScenario'}}
assert selftest['intentPerfect']['taskCodeAccuracy'] == 1.0 and selftest['intentWrong']['wrongToolCalls'] > 0
assert selftest['briefPerfect']['requiredCheckRecall'] == 1.0 and selftest['briefBad']['wrongApprovalExpressions'] > 0 and selftest['briefBad']['piiExposures'] > 0


def tokens(text):
    return len(text.encode('utf-8')) / 2.0


schema = lambda model: json.dumps(model.model_json_schema(), ensure_ascii=False)  # noqa: E731
EVIDENCE = 4 * 720  # four passages of up to 700 characters plus keys, Korean
intent_input = max(tokens(INTENT + schema(LlmIntentOutput) + c['message'] + ' '.join(c.get('recent', []))) for c in intents)
brief_input = max(tokens(BRIEF + schema(Brief) + json.dumps(c['facts'], ensure_ascii=False)) for c in briefs) + EVIDENCE * 1.5
impact_input = tokens(IMPACT + schema(Interpretation)) + 400 + 3 * 620 * 1.5
# Output: every schema field at its maximum length, Korean at 1.5 tokens per character.
intent_output = 300
brief_output = (500 + 8 * 120 + 6 * 120 + 6 * 240 + 500) * 1.5
impact_output = (400 + 4 * 240 + 6 * 240 + 6 * 240 + 240) * 1.5
PLAN = [('고객 의도 분류', len(intents), 5, intent_input, intent_output, 2), ('직원 검토 의견', len(briefs), 5, brief_input, brief_output, 1), ('업무기준 영향 분석', 8, 2, impact_input, impact_output, 1), ('안전성 추가 (인젝션·모순)', 6, 0, brief_input, brief_output, 1)]
price = PRICE['gpt-4.1-mini']
rows, total_calls, total_worst, total_retry = [], 0, 0.0, 0
for name, n, warm, inp, out, attempts in PLAN:
    calls = n + warm
    max_http = calls * attempts
    worst = max_http * (inp * price['input'] + out * price['output']) / 1_000_000
    rows.append({'path': name, 'cases': n, 'warmup': warm, 'plannedCalls': calls, 'maxHttpCallsWithRetry': max_http, 'maxInputTokensPerCall': round(inp), 'maxOutputTokensPerCall': round(out), 'worstCaseUsd': round(worst, 4)})
    total_calls += calls
    total_retry += max_http
    total_worst += worst
cost = {'model': 'gpt-4.1-mini', 'priceUsdPer1M': price, 'priceCheckedOn': '2026-11-09', 'method': 'tokens over-estimated as UTF-8 bytes / 2.0; output assumes every field at its maximum', 'rows': rows, 'plannedCalls': total_calls, 'maxHttpCallsWithRetry': total_retry, 'worstCaseUsd': round(total_worst, 4)}
save('selftest.json', selftest)
save('cost_estimate.json', cost)
print(json.dumps({'selftest': selftest, 'cost': cost}, ensure_ascii=False, indent=1)[:3800])
