"""Walks the policy-change path through real Spring and FastAPI APIs with a fake provider. Expectations come from gold_policy_cases.json."""
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
RES = HERE.parent / os.environ.get('E2E_RESULTS', 'results')
GOLD = json.loads((HERE.parent / 'datasets' / 'gold_policy_cases.json').read_text(encoding='utf-8'))
AI = HERE.parents[3] / 'ai-server'
sys.path.insert(0, str(AI))
SPRING, API, LLM = 'http://127.0.0.1:19511', 'http://127.0.0.1:19521', 'http://127.0.0.1:19522'
MY, DB = 't2-ax-mysql', 'banking_ax_ax'
ROWS, EVIDENCE = [], {}
seq = {'n': 0}


def sql(text):
    done = subprocess.run(['docker', 'exec', '-i', '-e', 'MYSQL_PWD', MY, 'mysql', '-uroot', '-N', '-B', '--default-character-set=utf8mb4', DB], input=text.encode(), capture_output=True, timeout=120)
    if done.returncode:
        raise RuntimeError(done.stderr.decode(errors='replace')[:300])
    return done.stdout.decode(errors='replace').strip()


def call(method, url, body=None, token=None, headers=None):
    head = {'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + token} if token else {}), **(headers or {})}
    request = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method, headers=head)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw, status = response.read(), response.status
    except urllib.error.HTTPError as error:
        raw, status = error.read(), error.code
    except Exception as error:  # noqa: BLE001
        return 0, {'transport': type(error).__name__}
    try:
        return status, json.loads(raw) if raw else None
    except ValueError:
        return status, None


def rid(tag):
    seq['n'] += 1
    return f'{tag}-{seq["n"]}'


def login(name):
    status, body = call('POST', SPRING + '/api/v1/auth/login', {'loginId': name, 'password': 'e2e-pass'})
    assert status == 200, (name, status)
    return (body.get('data') or body)['accessToken']


def record(area, check, status, evidence=''):
    ROWS.append({'area': area, 'check': check, 'status': status, 'evidence': str(evidence)[:500]})
    print(f'{status:16} {area:14} {check} :: {str(evidence)[:150]}', flush=True)


def verdict(area, check, ok, evidence=''):
    record(area, check, 'PASS' if ok else 'FAIL', evidence)


def fingerprint():
    raw = sql("select concat_ws(':',id,case_status,review_status,revision,version) from service_case order by id; select concat_ws(':',id,result,decided_at) from staff_review order by id; select count(*) from notification; select concat_ws(':',id,status) from required_document order by id; select count(*) from visit_reservation;")
    return hashlib.sha256(raw.encode()).hexdigest()[:16]


def bank(path):
    return call('POST', API + '/mock-bank/v1' + path, {}, headers={'X-Policy-Source-Token': os.environ['POLICY_SOURCE_TOKEN']})


def mode(name):
    call('POST', LLM + '/__mode', {'mode': name})


def llm_log():
    return call('GET', LLM + '/')[1]['log']


def data(pair):
    return (pair[1] or {}).get('data')


def last_facts():
    briefs = [row for row in llm_log() if row['schema'] == 'banking_ax_case_brief']
    return json.loads(briefs[-1]['user'])['facts'] if briefs else None


staff, second, nogrant = login('staff_demo'), login('e2e-reviewer2'), login('e2e-nogrant')
ids = {}
for line in sql("select e.payload->>'$.scenario', e.entity_id from event_log e where e.event_type='C02_DEMO_SEEDED'").splitlines():
    key, value = line.split('\t')
    ids[key] = int(value)
EVIDENCE['seededCases'] = ids
if len(ids) < 6:
    record('setup', '시드 Case 6건', 'FAIL', ids)
    Path(RES / 'e2e.json').write_text(json.dumps({'rows': ROWS}, ensure_ascii=False), encoding='utf-8')
    sys.exit(1)
P, R, N, U = ids['policy'], ids['rehearsal'], ids['cancel'], ids['review']
revision = lambda case: int(sql(f'select revision from service_case where id={case}'))  # noqa: E731


def brief(case, token=staff):
    return call('POST', f'{SPRING}/api/v1/staff/c02/{case}/ai-review', {'revision': revision(case), 'requestId': rid('brief')}, token)


def notices(case):
    return data(call('GET', f'{SPRING}/api/v1/staff/policy-changes/notices?taskCode=INHERITANCE_DEPOSIT&caseId={case}&requestId={rid("n")}', None, staff))


def sync():
    return data(call('POST', f'{SPRING}/api/v1/staff/policy-changes/sync', {'requestId': rid('sync')}, staff))


def change_id(version):
    rows = data(call('GET', f'{SPRING}/api/v1/staff/policy-changes?requestId={rid("o")}', None, staff))['changes']
    return next(row['id'] for row in rows if row['change']['sourceVersion'] == version and row['change']['externalPolicyId'].startswith('EDU-POLICY-C02'))


# Impact matrix: open and closed Cases, written straight into the isolated DB
parts = []
customer = int(sql("select id from `user` where login_id='customer_demo'"))
for case in GOLD['impactMatrix']:
    i = case['caseId']
    parts.append(f"insert into workflow(id,customer_id,status) values({i},{customer},'RUNNING');")
    parts.append(f"insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values({i},{i},'INHERITANCE_DEPOSIT',1,'HANDED_OFF','m-{i}');")
    parts.append(f"insert into service_case(id,work_item_id,customer_id,case_type,case_status,revision,visit_required,application,submitted_at) values({i},{i},{customer},'INHERITANCE_DEPOSIT','{case['status']}',1,true,json_object(),utc_timestamp(6));")
    for n, (visit, method) in enumerate(case['heirs']):
        parts.append(f"insert into heir(case_id,person_ref,name,relationship,is_applicant,visit_status,delegation_method) values({i},'h{n}','H','자녀',{'true' if n == 0 else 'false'},'{visit}','{method}');")
    if case['poaSlot']:
        parts.append(f"insert into required_document(work_item_id,document_type_code,requirement,status) values({i},'ABSENT_HEIR_POWER_OF_ATTORNEY','REQUIRED','NOT_SUBMITTED');")
sql('\n'.join(parts))

# Scenario A: baseline, change, analysis, approval
base_fp = fingerprint()
mode('normal')
call('POST', LLM + '/__clear', {})
status, body = brief(P)
facts = last_facts()
verdict('A-baseline', '변경 전 검토 의견 생성 (Spring→FastAPI→LLM→저장)', status == 200 and (data((status, body)) or {}).get('status') == 'SUCCEEDED', (status, (body or {}).get('error')))
verdict('A-baseline', '변경 전 입력에 승인 변경 없음', facts is not None and facts.get('approvedPolicyChanges') == [], facts and facts.get('approvedPolicyChanges'))
EVIDENCE['briefBefore'] = data((status, body))
published = bank('/admin/scenarios/c02-poa-v2')
first_sync = sync()
verdict('A-receive', 'HMAC 서명 검증 후 v2 수신', published[0] == 200 and first_sync.get('accepted') == 1, first_sync)
V2 = change_id('EDU-2026.2')
detail = data(call('GET', f'{SPRING}/api/v1/staff/policy-changes/{V2}?requestId={rid("d")}', None, staff))
found = {c['caseId'] for task in detail['impact'] for c in task['candidates']}
EVIDENCE['impactCandidates'] = len(found)
truth = {m['caseId'] for m in GOLD['impactMatrix'] if m['affected']} | {P, R, ids['visit-manual'] if False else P}
universe = {m['caseId'] for m in GOLD['impactMatrix']} | {P, R, N, U, ids['visit-manual'], ids['visit-expired']}
tp, fp_, fn = len(found & truth), len(found - truth), len(truth - found)
EVIDENCE['impact'] = {'universe': len(universe), 'truth': len(truth), 'tp': tp, 'fp': fp_, 'fn': fn, 'precision': round(tp / max(1, tp + fp_), 4), 'recall': round(tp / max(1, tp + fn), 4), 'nonAffected': len(universe - truth), 'falsePositiveRate': round(fp_ / max(1, len(universe - truth)), 4), 'unexpected': sorted(found - truth)[:10], 'missed': sorted(truth - found)[:10]}
verdict('A-impact', '영향 Case 식별 (Spring 규칙, 합성 66건)', fp_ == 0 and fn == 0, EVIDENCE['impact'])
call('POST', LLM + '/__clear', {})
analysis = data(call('POST', f'{SPRING}/api/v1/staff/policy-changes/{V2}/analysis', {'requestId': rid('a')}, staff))
sent = json.loads(llm_log()[-1]['user']) if llm_log() else {}
verdict('A-analysis', 'Agent가 변경 전후 원문과 RAG 근거를 입력받음', analysis.get('status') == 'SUCCEEDED' and '30일' in json.dumps(sent.get('change', {}), ensure_ascii=False) and bool(sent.get('currentBasis')), {'status': analysis.get('status'), 'basisDocs': [b.get('docId') for b in sent.get('currentBasis', [])]})
verdict('A-analysis', '분석 결과에 변경 차이(diff) 제공', bool(analysis.get('diff')), analysis.get('diff'))
pending = notices(P)
verdict('A-unapproved', '승인 전 approved 안내 없음, pending으로만 표시', pending['approved'] == [] and len(pending['pending']) == 1, {k: len(pending[k]) for k in ('approved', 'pending')})
brief(P)
facts = last_facts()
verdict('A-unapproved', '승인 전 LLM 입력에 v2 없음', facts.get('approvedPolicyChanges') == [] and 'EDU-2026.2' not in llm_log()[-1]['user'], facts.get('approvedPolicyChanges'))
verdict('P0-agent', '수신·분석·의견 생성이 Case·결정·알림을 바꾸지 않음', fingerprint() == base_fp, 'fingerprint 동일')
status_block, _ = call('POST', f'{SPRING}/api/v1/staff/policy-changes/{V2}/decision', {'requestId': rid('dn'), 'decision': 'NOTICE_APPROVED', 'note': 'x'}, nogrant)
verdict('P0-auth', '권한 없는 직원의 승인 차단', status_block == 403, status_block)
decided = call('POST', f'{SPRING}/api/v1/staff/policy-changes/{V2}/decision', {'requestId': rid('dec'), 'decision': 'NOTICE_APPROVED', 'note': '교육용 승인'}, staff)
again = call('POST', f'{SPRING}/api/v1/staff/policy-changes/{V2}/decision', {'requestId': rid('dec2'), 'decision': 'NOTICE_APPROVED', 'note': '교육용 승인'}, staff)
other = call('POST', f'{SPRING}/api/v1/staff/policy-changes/{V2}/decision', {'requestId': rid('dec3'), 'decision': 'NO_ACTION', 'note': 'x'}, staff)
verdict('A-approval', '직원 승인 저장, 같은 결정 재전송 일관', decided[0] == 200 and again[0] == 200 and data(decided)['decision'] == data(again)['decision'], decided[0])
verdict('A-approval', '다른 결정으로 덮어쓰기 거부', other[0] >= 400 and (other[1].get('error') or {}).get('code') == 'POLICY_ALREADY_DECIDED', other[0])
after_notice = notices(P)
verdict('A-approval', '승인 후 영향 Case에 안내 표시', [row['sourceVersion'] for row in after_notice['approved']] == ['EDU-2026.2'], after_notice['approved'])
status, body = brief(P)
facts = last_facts()
stored_after = data((status, body))
EVIDENCE['briefAfter'] = stored_after
EVIDENCE['factsAfterApproval'] = facts.get('approvedPolicyChanges')
verdict('A-brief', '승인 후 재생성 입력에 v2 버전 전달', [row.get('sourceVersion') for row in facts.get('approvedPolicyChanges', [])] == ['EDU-2026.2'], facts.get('approvedPolicyChanges'))
sent_text = json.dumps(facts.get('approvedPolicyChanges'), ensure_ascii=False)
detail_passed = '30일' in sent_text
record('A-brief', '필수 변경사항(작성일 30일)이 검토 의견 입력에 전달', 'PASS' if detail_passed else 'PARTIAL', '요약 문구만 전달, 변경 전후 원문과 addedCheckItems 미전달' if not detail_passed else sent_text)
checks_text = ' '.join(stored_after.get('staffChecks', []))
verdict('A-brief', '직원 확인 항목에 승인 변경 버전 표시', 'EDU-2026.2' in checks_text, [c for c in stored_after.get('staffChecks', []) if 'EDU' in c])
record('A-brief', '직원 확인 항목에 구체 조치(30일 이내 확인) 명시', 'PASS' if '30일' in checks_text else 'PARTIAL', '승인 변경 반영 여부를 보라는 일반 문구만 생성' if '30일' not in checks_text else checks_text)
runs = sql(f"select attempt_count, prompt_version, json_unquote(json_extract(report,'$.analyzedAt')) from agent_review_run where case_id={P} and check_type='STAFF_BRIEF'")
EVIDENCE['storedBriefRows'] = runs
hist = call('GET', f'{SPRING}/api/v1/staff/c02/{P}/ai-review/history?requestId={rid("h")}', None, staff)
entries = data(hist) or []
EVIDENCE['history'] = [{'analyzedAt': e.get('analyzedAt'), 'policies': e.get('approvedPolicies'), 'prompt': e.get('promptVersion'), 'hasChecks': len(e.get('staffChecks', []))} for e in entries]
record('A-history', '변경 전후 검토 의견 이력 보존', 'PASS' if hist[0] == 200 and len(entries) >= 3 else 'NOT_IMPLEMENTED', f'history {len(entries)}건, 최신 행 {runs}')
report_keys = json.loads(sql(f"select report from agent_review_run where case_id={P} and check_type='STAFF_BRIEF'"))
record('A-history', '검토 의견 저장본에 적용 정책 버전 기록', 'PASS' if [p.get('sourceVersion') for p in report_keys.get('approvedPolicies', [])] == ['EDU-2026.2'] else 'NOT_IMPLEMENTED', report_keys.get('approvedPolicies'))
versions = [[p.get('sourceVersion') for p in e.get('approvedPolicies', [])] for e in entries]
verdict('A-compare', '이력에서 승인 전 의견과 승인 후 의견을 구분', versions[:2] == [[], []] and versions[-1] == ['EDU-2026.2'], versions)
before_checks, after_checks = entries[0].get('staffChecks', []), stored_after.get('staffChecks', [])
new_lines = [c for c in after_checks if c not in before_checks]
EVIDENCE['briefDiff'] = {'added': new_lines, 'removed': [c for c in before_checks if c not in after_checks]}
verdict('A-compare', '승인 후 의견에만 변경 확인 항목 추가', any('30일' in c for c in new_lines) and not any('30일' in c for c in before_checks), new_lines)
verdict('P0-agent', '승인 후에도 Case·결정·알림 불변', fingerprint() == base_fp, 'fingerprint 동일')

# Scenario B: no impact
for name, case in (('전원 방문', N), ('잠정 불참·위임 없음', U)):
    row = notices(case)
    verdict('B-excluded', f'{name}: 안내 대상 제외', row['approved'] == [] and row['pending'] == [], {k: len(row[k]) for k in ('approved', 'pending')})
    brief(case)
    verdict('B-excluded', f'{name}: LLM 입력에 승인 변경 없음', last_facts().get('approvedPolicyChanges') == [], last_facts().get('approvedPolicyChanges'))
mode('adversarial')
status, body = brief(N)
out = data((status, body)) or {}
EVIDENCE['adversarialNotAffected'] = out
checks = ' '.join(out.get('staffChecks', []))
verdict('B-guard', '모델이 위임 항목을 만들어도 전원 방문 Case에서 제거', not any(w in checks for w in GOLD['scenarios']['B']['forbiddenInChecks']), out.get('staffChecks'))
visible = ' '.join([out.get('summary', ''), *out.get('confirmed', []), out.get('customerDraft', '')])
verdict('P0-assert', '승인·지급 확정, 자동 확인 표현 차단', not any(w in visible for w in GOLD['forbiddenAssertions']), visible[:200])
verdict('P0-assert', '지어낸 근거 문서 제거', all(b.get('docId') != 'MADE-UP' for b in out.get('basis', [])), out.get('basis'))
status, body = brief(P)
out = data((status, body)) or {}
visible = ' '.join([out.get('summary', ''), out.get('customerDraft', ''), *out.get('staffChecks', [])])
verdict('P0-unapproved', '모델이 미승인 45일을 지어내도 차단', '45일' not in visible and 'EDU-2026.3' not in visible, visible[:220])
mode('normal')

# Scenario C: v3 stays unapproved
bank('/admin/scenarios/c02-poa-v3')
c_sync = sync()
V3 = change_id('EDU-2026.3')
row = notices(P)
verdict('C-unapproved', 'v3 수신 후 v2 승인 안내 유지', [x['sourceVersion'] for x in row['approved']] == ['EDU-2026.2'], row['approved'])
verdict('C-unapproved', 'v3는 pending으로만 구분 표시', [x['sourceVersion'] for x in row['pending']] == ['EDU-2026.3'], row['pending'])
brief(P)
log_text = llm_log()[-1]['user']
verdict('P0-unapproved', 'v3가 검토 의견 입력에 들어가지 않음', 'EDU-2026.3' not in log_text and '45일' not in log_text, [x.get('sourceVersion') for x in last_facts().get('approvedPolicyChanges', [])])
verdict('C-unapproved', 'v3 수신 동기화 정상', c_sync.get('accepted') == 1, c_sync)

# Retransmission and bad events
bank('/admin/replay-last')
replay_sync = sync()
verdict('reliability', '동일 이벤트 재전송은 중복 처리', replay_sync.get('duplicate') == 1 and replay_sync.get('accepted') == 0, replay_sync)
bank('/admin/scenarios/unknown-change-type')
verdict('reliability', '알 수 없는 변경 유형 격리', sync().get('quarantined') == 1)
bank('/admin/scenarios/out-of-scope')
verdict('reliability', '대상 외 업무 분리', sync().get('outOfScope') == 1)
old = revision(P)
stale = call('POST', f'{SPRING}/api/v1/staff/c02/{P}/ai-review', {'revision': old + 50, 'requestId': rid('stale')}, staff)
verdict('reliability', '오래된 revision의 의견 생성 거부', stale[0] >= 400, (stale[0], (stale[1].get('error') or {}).get('code')))

# Authorization
routes = [('GET', f'/api/v1/staff/c02/{P}/staff-case?requestId={rid("x")}', None), ('POST', f'/api/v1/staff/c02/{P}/ai-review', {'revision': old, 'requestId': rid('x')}), ('GET', f'/api/v1/staff/policy-changes?requestId={rid("x")}', None), ('GET', f'/api/v1/staff/c02/cases?requestId={rid("x")}', None)]
minjun, jiwoo = login('demo_minjun'), login('demo_jiwoo')
for method, path, body in routes:
    verdict('P0-auth', f'토큰 없음 401 {path[:40]}', call(method, SPRING + path, body)[0] == 401)
    verdict('P0-auth', f'고객 토큰 403 {path[:40]}', call(method, SPRING + path, body, minjun)[0] == 403)
    verdict('P0-auth', f'권한 없는 직원 403 {path[:40]}', call(method, SPRING + path, body, nogrant)[0] == 403)
document = sql(f'select min(df.id) from document_file df join required_document rd on rd.id=df.required_document_id join service_case sc on sc.work_item_id=rd.work_item_id where sc.id={P}')
foreign = call('GET', f'{SPRING}/api/v1/customer/c02/{P}/documents/{document}/content', None, jiwoo)
verdict('P0-auth', '다른 고객의 제출 서류 접근 차단', foreign[0] in (403, 404), foreign[0])
own_list = data(call('GET', f'{SPRING}/api/v1/customer/c02/cases?requestId={rid("x")}', None, jiwoo)) or []
verdict('P0-auth', '고객 목록에 본인 Case만 표시', [row['caseId'] for row in own_list] == [R], [row['caseId'] for row in own_list])
start = call('POST', f'{SPRING}/api/v1/staff/c02/{R}/start-review', {'revision': revision(R), 'requestId': rid('st')}, staff)
file_row = data(call('GET', f'{SPRING}/api/v1/staff/c02/{R}/document-reviews?requestId={rid("x")}', None, staff))['documents'][0]
intrude = call('PUT', f"{SPRING}/api/v1/staff/c02/{R}/documents/{file_row['documentFileId']}/review", {'fileVersion': file_row['fileVersion'], 'result': 'CONFIRMED', 'reason': None, 'expectedReviewId': None, 'requestId': rid('rv')}, second)
verdict('P0-auth', '검토 담당이 아닌 직원의 서류 판정 차단', start[0] == 200 and intrude[0] == 403, (start[0], intrude[0]))
verdict('P0-agent', '검토 의견은 원본 확인 상태를 바꾸지 않음', sql(f"select count(*) from required_document rd join service_case sc on sc.work_item_id=rd.work_item_id where sc.id={R} and rd.human_checked_by is not null") == '0')

# Only a staff decision reaches the customer
notices_before = int(sql(f'select count(*) from notification where case_id={R}'))
brief(R)
verdict('control', 'AI 의견 생성만으로 고객 알림 없음', int(sql(f'select count(*) from notification where case_id={R}')) == notices_before)
draft_message = '제출하신 서류를 확인했어요. 방문 예약을 진행해 주세요.'
decide = call('POST', f'{SPRING}/api/v1/staff/c02/{R}/staff-decision', {'revision': revision(R), 'requestId': rid('sd'), 'outcome': 'PROCEED', 'customerMessage': draft_message, 'exceptionReason': '교육용 시나리오: 서류 미확인 상태 등록'}, staff)
stored_message = sql(f"select opinion_final from staff_review where case_id={R} order by id desc limit 1")
verdict('control', '직원 결정 저장 후 Case·알림 변경', decide[0] == 200 and sql(f'select case_status from service_case where id={R}') == 'REVIEW_PASSED' and int(sql(f'select count(*) from notification where case_id={R}')) == notices_before + 1, (decide[0], (decide[1] or {}).get('error')))
verdict('control', '직원이 입력한 문구 그대로 저장', draft_message in stored_message or decide[0] != 200, stored_message[:80])
replay = call('POST', f'{SPRING}/api/v1/staff/c02/{R}/staff-decision', {'revision': revision(R), 'requestId': rid('sd2'), 'outcome': 'REQUIREMENT_FAILED', 'customerMessage': '바꿈', 'exceptionReason': None}, staff)
verdict('control', '확정 결정 뒤 다른 결정 거부', replay[0] >= 400, (replay[0], (replay[1] or {}).get('error', {}).get('code')))

# Failures keep the Case intact
fail_fp = fingerprint()
for name in ('fail500', 'invalid'):
    mode(name)
    status, body = brief(P)
    out = data((status, body)) or {}
    verdict('failure', f'LLM {name}: 규칙 기반 의견으로 대체, 상태 보존', status == 200 and out.get('method') == 'RULE_FALLBACK' and fingerprint() == fail_fp, (status, out.get('method')))
mode('fail500')
analysis = data(call('POST', f'{SPRING}/api/v1/staff/policy-changes/{V3}/analysis', {'requestId': rid('af')}, staff))
verdict('failure', 'LLM 장애 시 영향 분석도 규칙 기반', analysis.get('status') == 'SUCCEEDED' and analysis.get('method') == 'RULE_FALLBACK', {k: analysis.get(k) for k in ('status', 'method')})
mode('normal')

# Withdrawal, then the unauthenticated FastAPI routes
bank('/admin/scenarios/c02-poa-withdrawn')
sync()
row = notices(P)
verdict('C-withdrawn', '은행 철회 시 승인 안내 중단', row['approved'] == [], [x['sourceVersion'] for x in row['approved']])
count = len(llm_log())
classify = call('POST', API + '/api/v1/customer-agent/classify', {'message': '대출 조회해줘'})
open_paths = [{'path': path, 'headers': [p['name'] for op in methods.values() for p in op.get('parameters', []) if p.get('in') == 'header']} for path, methods in call('GET', API + '/openapi.json')[1]['paths'].items() if path.startswith('/api/v1/rag/')]
verdict('P0-auth', 'FastAPI 의도 분류는 토큰 없이 호출 불가', classify[0] in (401, 403), f'무인증 http={classify[0]}, LLM 호출 {len(llm_log()) - count}회 발생')
verdict('P0-auth', 'FastAPI /api/v1/rag/* 인증', all(row['headers'] for row in open_paths), open_paths)
leak = call('POST', API + '/api/v1/rag/search', {'query': '채무조정 합의 해지 효과', 'task_code': 'DEBT_ADJUSTMENT', 'audience': 'staff', 'top_k': 5})
leaked = sorted({row['doc_id'] for row in (leak[1] or {}).get('results', [])} & {'C01-03', 'C01-05'})
verdict('P0-auth', '무인증 호출로 직원 전용(STAFF_ONLY) 업무문서 조회 불가', not leaked, {'http': leak[0], 'staffOnlyDocs': leaked})
for name, headers_, body_, expected in (
    ('Spring이 발급한 고객 JWT는 직원 문서 요청 403', {'Authorization': 'Bearer ' + minjun}, {'audience': 'staff'}, 403),
    ('Spring이 발급한 고객 JWT는 고객 문서 조회 200', {'Authorization': 'Bearer ' + minjun}, {'audience': 'customer'}, 200),
    ('Spring이 발급한 직원 JWT는 직원 문서 조회 200', {'Authorization': 'Bearer ' + staff}, {'audience': 'staff'}, 200),
    ('직원 서비스 토큰은 직원 문서 조회 200', {'X-Staff-Agent-Token': os.environ['STAFF_AGENT_GATEWAY_TOKEN']}, {'audience': 'staff'}, 200),
    ('고객 서비스 토큰은 직원 문서 요청 403', {'X-Customer-Agent-Token': os.environ['E2E_TOKEN']}, {'audience': 'staff'}, 403),
    ('위조 JWT 401', {'Authorization': 'Bearer ' + staff[:-4] + 'AAAA'}, {'audience': 'staff'}, 401),
):
    got = call('POST', API + '/api/v1/rag/search', {'query': '채무조정 합의 해지 효과', 'task_code': 'DEBT_ADJUSTMENT', 'top_k': 5, **body_}, headers=headers_)
    verdict('AUTH-fixed', name, got[0] == expected, got[0])
customer_docs = call('POST', API + '/api/v1/rag/search', {'query': '채무조정 합의 해지 효과', 'task_code': 'DEBT_ADJUSTMENT', 'top_k': 5, 'audience': 'customer'}, headers={'Authorization': 'Bearer ' + minjun})
verdict('AUTH-fixed', '고객 조회 결과에 직원 전용 문서 없음', not ({row['doc_id'] for row in customer_docs[1]['results']} & {'C01-03', 'C01-05'}), sorted({row['doc_id'] for row in customer_docs[1]['results']}))
verdict('AUTH-fixed', '내부 토큰 있는 의도 분류는 정상', call('POST', API + '/api/v1/customer-agent/classify', {'message': '대출 조회해줘'}, headers={'X-Customer-Agent-Token': os.environ['E2E_TOKEN']})[0] == 200)
verdict('AUTH-fixed', '직원 순위 경로는 서비스 토큰 없으면 403', call('POST', API + '/api/v1/staff-ranking', {'candidateIds': [1], 'taskCode': 'INHERITANCE_DEPOSIT'})[0] == 403)
verdict('AUTH-fixed', 'Spring→FastAPI 직원 Agent 호출 계약 유지', len([r for r in ROWS if r['area'] == 'A-baseline' and r['status'] == 'PASS']) == 2)
EVIDENCE['deployment'] = {'deploymentFilesInGit': subprocess.run(['git', 'ls-files'], cwd=AI.parent, capture_output=True, text=True).stdout.count('docker-compose'), 'current9001Binding': '로컬 127.0.0.1'}

# PII in everything sent to the provider
names = ['김민준', '김서윤', '오지우', '오민재', '정하늘', '정하준', '이서연', '정미숙', '이도현', '김영수', '오상철', '정만수', '이정호', 'demo-', '010-']
log = llm_log()
staff_prompts = [row for row in log if row['schema'] != 'banking_ax_customer_intent']
hits = sorted({n for row in staff_prompts for n in names if n in row['user']})
pattern = [row['schema'] for row in staff_prompts if re.search(r'\d{6}-[1-4]\d{6}|\d{3}-\d{2,6}-\d{4,}', row['user'])]
verdict('P0-pii', f'직원 LLM 입력에 시드 개인정보 없음 ({len(staff_prompts)}건 검사)', not hits and not pattern, {'hits': hits, 'patterns': pattern})
secrets = [v for v in (os.environ['E2E_TOKEN'], os.environ['POLICY_SOURCE_TOKEN'], os.environ['STAFF_AGENT_GATEWAY_TOKEN']) if any(v in row['user'] for row in staff_prompts)]
verdict('P0-pii', '직원 LLM 입력에 인증정보 없음', not secrets, len(secrets))

# Conflict detector coverage on the fixed forms
os.environ.setdefault('OPENAI_API_KEY', '')
from app.staff_agent.basis_conflict import find_conflicts  # noqa: E402

forms = []
for item in GOLD['conflictForms']:
    got = bool(find_conflicts([{'docId': 'A', 'section': 's', 'text': item['a']}, {'docId': 'B', 'section': 's', 'text': item['b']}]))
    forms.append({'form': item['form'], 'expected': item['isConflict'], 'detected': got})
EVIDENCE['conflictForms'] = forms
tp = sum(f['expected'] and f['detected'] for f in forms)
fn_c = sum(f['expected'] and not f['detected'] for f in forms)
fp_c = sum(not f['expected'] and f['detected'] for f in forms)
record('D-conflict', f'충돌 형태 {len(forms)}종 감지 범위', 'PARTIAL' if fn_c or fp_c else 'PASS', {'detected': tp, 'missedConflicts': [f['form'] for f in forms if f['expected'] and not f['detected']], 'falseAlarm': [f['form'] for f in forms if not f['expected'] and f['detected']]})

# FastAPI down: Spring must report it and keep everything
stop = subprocess.run('netstat -ano', shell=True, capture_output=True).stdout.decode('cp949', errors='replace')
pid = next((line.split()[-1] for line in stop.splitlines() if '127.0.0.1:19521' in line and 'LISTENING' in line), None)
fp_down = fingerprint()
before_report = sql(f"select md5(report) from agent_review_run where case_id={P} and check_type='STAFF_BRIEF'")
subprocess.run(f'taskkill /F /T /PID {pid}', shell=True, capture_output=True)
status, body = brief(P)
out = data((status, body)) or {}
verdict('failure', 'FastAPI 중단: FAILED 응답, Case·기존 의견 보존', status == 200 and out.get('status') == 'FAILED' and fingerprint() == fp_down and sql(f"select md5(report) from agent_review_run where case_id={P} and check_type='STAFF_BRIEF'") == before_report, (status, out))

counts = {}
for row in ROWS:
    counts[row['status']] = counts.get(row['status'], 0) + 1
(RES / 'e2e.json').write_text(json.dumps({'mode': '실제 Spring·FastAPI·MySQL + 가짜 LLM. 모델 품질 측정이 아니다', 'counts': counts, 'rows': ROWS, 'evidence': EVIDENCE}, ensure_ascii=False, indent=1), encoding='utf-8')
print(counts, flush=True)
