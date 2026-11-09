"""Realistic reservation load against a throwaway Spring + MySQL. See run_profile.sh; refuses any shared port.

Each measured run starts from an empty reservation state. Lock wait and transaction time come from MySQL performance_schema:
the statement time of the branch FOR UPDATE, and the enclosing transaction. HTTP results are checked against the database.
"""
from __future__ import annotations

import argparse
import datetime as dt
import http.client
import json
import os
import random
import subprocess
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--profile', required=True)
parser.add_argument('--smoke', action='store_true')
args = parser.parse_args()
PORT = 19412
assert PORT not in (18081, 18082, 9001, 5175, 13307)
MYSQL, DB = 't2-res-mysql', 'banking_ax_res'
SPRING = 't2-res-spring'
HASH = os.environ['RES_HASH']
OUT = Path(__file__).resolve().parents[1] / 'results'
KST = dt.timezone(dt.timedelta(hours=9))
HOURS = (10, 11, 13, 14, 15)
BRANCH_STAFF = {1: 2, 2: 3, 3: 5, 11: 30, **{b: 3 for b in range(21, 41)}, 61: 100, 62: 2}
CUSTOMERS = 400
REPS = {'A': 1, 'B': 1, 'L': 2, 'C': 1} if args.smoke else {'A': 3, 'B': 5, 'L': 10, 'C': 3}


def docker_sql(text: str, schema: str = DB) -> str:
    done = subprocess.run(['docker', 'exec', '-i', '-e', 'MYSQL_PWD', MYSQL, 'mysql', '-uroot', '-N', '-B', '--default-character-set=utf8mb4', schema], input=text.encode(), capture_output=True, timeout=180)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.decode(errors='replace')[:400])
    return done.stdout.decode(errors='replace').strip()


def days() -> list[dt.date]:
    today = dt.datetime.now(KST).date()
    return [today + dt.timedelta(n) for n in range(2, 8) if (today + dt.timedelta(n)).weekday() < 5]


DAYS = days()
SLOTS = [dt.datetime(d.year, d.month, d.day, h, tzinfo=KST) for d in DAYS for h in HOURS]
iso = lambda t: t.isoformat()  # noqa: E731
utc = lambda t: t.astimezone(dt.timezone.utc).strftime('%Y-%m-%d %H:%M:%S')  # noqa: E731
FAR = SLOTS[0]


def seed() -> None:
    staff_rows, staff_id, tables = [], 100000, ['event_log', 'notification', 'visit_reservation', 'case_revision', 'service_case', 'work_item', 'workflow', 'user', 'branch']
    for branch, count in BRANCH_STAFF.items():
        for n in range(count):
            staff_id += 1
            staff_rows.append(f"({staff_id},'staff-{staff_id}','x','STAFF','S{staff_id}',{branch})")
    parts = ['set foreign_key_checks=0;'] + [f'delete from `{t}`;' for t in tables] + ['set foreign_key_checks=1;']
    parts.append('insert into branch(id,branch_code,name,address,is_active) values' + ','.join(f"({b},'B{b}','Branch {b}','isolated',true)" for b in BRANCH_STAFF) + ';')
    parts.append('insert into `user`(id,login_id,password_hash,`role`,name,branch_id) values' + ','.join(staff_rows) + ';')
    n = range(1, CUSTOMERS + 1)
    parts.append('insert into `user`(id,login_id,password_hash,`role`,name) values' + ','.join(f"({1000+i},'load-c{i}','{HASH}','CUSTOMER','C{i}')" for i in n) + ';')
    parts.append('insert into workflow(id,customer_id,status) values' + ','.join(f"({2000+i},{1000+i},'RUNNING')" for i in n) + ';')
    parts.append('insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values' + ','.join(f"({3000+i},{2000+i},'INHERITANCE_DEPOSIT',1,'HANDED_OFF','w-{i}')" for i in n) + ';')
    parts.append('insert into service_case(id,work_item_id,customer_id,case_type,case_status,revision,visit_required,application,result_notified_at) values' + ','.join(f"({7000+i},{3000+i},{1000+i},'INHERITANCE_DEPOSIT','REVIEW_PASSED',1,true,json_object(),utc_timestamp(6))" for i in n) + ';')
    docker_sql('\n'.join(parts))


tokens: dict[int, str] = {}
staff_of = {}


def login_all() -> None:
    def one(i):
        conn = http.client.HTTPConnection('127.0.0.1', PORT, timeout=120)
        conn.request('POST', '/api/v1/auth/login', json.dumps({'loginId': f'load-c{i}', 'password': 'load-password'}), {'Content-Type': 'application/json'})
        response = conn.getresponse()
        body = json.loads(response.read().decode())
        conn.close()
        if response.status != 200:
            raise RuntimeError(f'login {response.status}')
        tokens[i] = (body.get('data') or body)['accessToken']
    with ThreadPoolExecutor(8) as pool:
        list(pool.map(one, range(1, CUSTOMERS + 1)))


class Recorder:
    def __init__(self):
        self.rows, self.lock, self.now, self.peak = [], threading.Lock(), 0, 0

    def call(self, op, method, path, body, customer, conn=None):
        conn = conn or http.client.HTTPConnection('127.0.0.1', PORT, timeout=120)
        headers = {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tokens[customer]}
        with self.lock:
            self.now += 1
            self.peak = max(self.peak, self.now)
        wall, started = time.time(), time.perf_counter()
        try:
            conn.request(method, path, None if body is None else json.dumps(body), headers)
            response = conn.getresponse()
            raw, status = response.read().decode(errors='replace'), response.status
        except Exception as failure:  # noqa: BLE001
            raw, status = type(failure).__name__, 0
        finally:
            conn.close()
        ms = (time.perf_counter() - started) * 1000
        with self.lock:
            self.now -= 1
        try:
            parsed = json.loads(raw)
        except ValueError:
            parsed = None
        code = (parsed.get('error') or {}).get('code') if isinstance(parsed, dict) else None
        row = {'op': op, 'status': status, 'ms': ms, 'code': code, 't': wall, 'id': ((parsed or {}).get('data') or {}).get('id') if isinstance(parsed, dict) and isinstance(parsed.get('data'), dict) else None, 'raw': raw[:200] if status >= 500 or status == 0 else ''}
        with self.lock:
            self.rows.append(row)
        return row, parsed


def counters() -> dict:
    raw = docker_sql("select name,count from information_schema.innodb_metrics where name in ('lock_deadlocks','lock_timeouts','lock_row_lock_waits','lock_row_lock_time');", 'information_schema')
    return {line.split('\t')[0]: int(line.split('\t')[1]) for line in raw.splitlines()}


def cgroup(container: str) -> dict:
    raw = subprocess.run(['docker', 'exec', container, 'sh', '-c', 'cat /sys/fs/cgroup/cpu.stat; echo PEAK $(cat /sys/fs/cgroup/memory.peak 2>/dev/null); echo CUR $(cat /sys/fs/cgroup/memory.current)'], capture_output=True, text=True, timeout=30).stdout
    values = dict(line.split(None, 1) for line in raw.splitlines() if len(line.split(None, 1)) == 2)
    return {'cpuUs': int(values['usage_usec']), 'throttledUs': int(values.get('throttled_usec', 0)), 'peakBytes': int(values['PEAK']) if values.get('PEAK', '').strip().isdigit() else None, 'currentBytes': int(values['CUR'])}


def reset(preseed: str = '') -> None:
    docker_sql('set foreign_key_checks=0; delete from event_log; delete from notification; delete from visit_reservation; set foreign_key_checks=1;' + preseed)
    docker_sql('truncate table events_statements_history_long; truncate table events_transactions_history_long;', 'performance_schema')


def lock_samples() -> dict:
    raw = docker_sql("select round(s.TIMER_WAIT/1e9,3), ifnull(round(t.TIMER_WAIT/1e9,3),-1) from performance_schema.events_statements_history_long s left join performance_schema.events_transactions_history_long t on t.THREAD_ID=s.THREAD_ID and t.TIMER_START<=s.TIMER_END and s.TIMER_START<=t.TIMER_END where s.SQL_TEXT like 'select id from branch where id=% for update%';", 'performance_schema')
    waits, transactions = [], []
    for line in raw.splitlines():
        a, b = line.split('\t')
        waits.append(float(a))
        if float(b) >= 0:
            transactions.append(float(b))
    return {'lockWaitMs': waits, 'txMs': transactions}


def db_state() -> dict:
    active = "status not in ('CANCELED','EXPIRED')"
    raw = docker_sql(f"""select (select count(*) from visit_reservation where status='CONFIRMED'),(select count(*) from visit_reservation where status='CANCELED'),
      (select count(*) from visit_reservation where status='NEEDS_MANUAL_ASSIGNMENT'),
      (select count(*) from (select 1 from visit_reservation where {active} and assigned_staff_id is not null group by assigned_staff_id,starts_at having count(*)>1) x),
      (select count(*) from (select 1 from visit_reservation where {active} group by case_id having count(*)>1) x),
      (select count(*) from event_log where event_type='VISIT_ASSIGNED'),(select count(*) from notification where type='VISIT_CONFIRMED'),(select count(*) from event_log where event_type='VISIT_CHANGED');""")
    keys = ('confirmed', 'canceled', 'manual', 'dupStaffSlot', 'dupCase', 'assignedEvents', 'confirmNotices', 'changedEvents')
    return dict(zip(keys, (int(x) for x in raw.split('\t'))))


def resources() -> dict:
    return {'mysql': cgroup(MYSQL), 'spring': cgroup(SPRING), 'driverCpuSec': time.process_time()}


def classify(row) -> str:
    if 200 <= row['status'] < 300:
        return 'ok'
    if row['status'] == 0 or row['status'] >= 500:
        return 'system'
    if row['status'] in (400, 404, 409) and row['code']:
        return 'business'
    return 'clientOther'


RESULTS = []


def measure(name, rep, meta, body, check, preseed=''):
    """body(recorder) runs the load; check(state, rows) returns the failed expectations, empty when correct."""
    reset(preseed)
    recorder = Recorder()
    before_counters, before = counters(), resources()
    wall = time.time()
    body(recorder)
    elapsed = time.time() - wall
    after_counters, after = counters(), resources()
    state = db_state()
    lock = lock_samples()
    failures = check(state, recorder.rows)
    by_op = {}
    for row in recorder.rows:
        by_op.setdefault(row['op'], []).append(row)
    result = {'scenario': name, 'rep': rep, 'profile': args.profile, 'meta': meta, 'startedAt': dt.datetime.fromtimestamp(wall, KST).isoformat(), 'durationSec': round(elapsed, 3), 'peakInFlight': recorder.peak,
              'ops': {op: {'ms': [round(r['ms'], 3) for r in rows], 'classes': dict(Counter(classify(r) for r in rows)), 'codes': dict(Counter(str(r['status']) + ':' + str(r['code']) for r in rows if not 200 <= r['status'] < 300))} for op, rows in by_op.items()},
              'db': state, 'lock': lock, 'innodb': {k: after_counters[k] - before_counters[k] for k in after_counters},
              'resources': {'mysqlCpuSec': round((after['mysql']['cpuUs'] - before['mysql']['cpuUs']) / 1e6, 3), 'springCpuSec': round((after['spring']['cpuUs'] - before['spring']['cpuUs']) / 1e6, 3), 'driverCpuSec': round(after['driverCpuSec'] - before['driverCpuSec'], 3),
                            'mysqlThrottledSec': round((after['mysql']['throttledUs'] - before['mysql']['throttledUs']) / 1e6, 3), 'springThrottledSec': round((after['spring']['throttledUs'] - before['spring']['throttledUs']) / 1e6, 3), 'mysqlPeakMb': round((after['mysql']['peakBytes'] or after['mysql']['currentBytes']) / 1048576, 1), 'springPeakMb': round((after['spring']['peakBytes'] or after['spring']['currentBytes']) / 1048576, 1)},
              'failedExpectations': failures, 'systemErrorSamples': [r['raw'] for r in recorder.rows if classify(r) == 'system'][:3]}
    RESULTS.append(result)
    (OUT / f'{args.profile}.json').write_text(json.dumps(RESULTS, ensure_ascii=False), encoding='utf-8')
    print(name, rep, 'dur', result['durationSec'], 'peak', recorder.peak, 'ok' if not failures else 'FAILED ' + str(failures), {k: v['classes'] for k, v in result['ops'].items()}, 'deadlocks', result['innodb']['lock_deadlocks'], flush=True)


def ok_count(rows, op):
    return sum(r['op'] == op and 200 <= r['status'] < 300 for r in rows)


def no_system(rows):
    return [] if not any(r['status'] == 0 or r['status'] >= 500 for r in rows) else ['system-error']


def consistency(state):
    return [f'{k}={state[k]}' for k in ('dupStaffSlot', 'dupCase', 'manual') if state[k]]


def scheduled(start_offsets, work):
    begin = time.perf_counter() + 0.2
    def wrap(item):
        index, offset = item
        delay = begin + offset - time.perf_counter()
        if delay > 0:
            time.sleep(delay)
        work(index)
    with ThreadPoolExecutor(max_workers=len(start_offsets)) as pool:
        list(pool.map(wrap, enumerate(start_offsets)))


def scenario_a(label, rep, sessions, rate):
    rng = random.Random(1000 * rep + sessions)
    offsets, now = [], 0.0
    for _ in range(sessions):
        now += rng.expovariate(rate)
        offsets.append(now)
    plans = [(rng.choice((1, 2, 3)), rng.choice(DAYS), rng.choice(DAYS), rng.random() < .25, rng.uniform(.3, 1.0), rng.uniform(.3, 1.0)) for _ in range(sessions)]

    slug = label.replace(' ', '_').replace('/', '-')

    def body(rec):
        def session(i):
            n = i + 1
            branch, day, other, change, think1, think2 = plans[i]
            _, avail = rec.call('availability', 'GET', f'/api/v1/customer/reservations/availability?branchId={branch}&date={day}&requestId=a{rep}-{slug}-{n}', None, n)
            free = [s for s in ((avail or {}).get('data') or []) if s.get('available')]
            if not free:
                return
            time.sleep(think1)
            row, _ = rec.call('create', 'POST', '/api/v1/customer/reservations', {'requestId': f'{slug}-{rep}-c{n}', 'caseId': 7000 + n, 'branchId': branch, 'startsAt': random.Random(n * 7 + rep).choice(free)['startsAt']}, n)
            rec.call('list', 'GET', f'/api/v1/customer/reservations?requestId=l{rep}-{slug}-{n}', None, n)
            if change and 200 <= row['status'] < 300:
                time.sleep(think2)
                _, again = rec.call('availability', 'GET', f'/api/v1/customer/reservations/availability?branchId={branch}&date={other}&requestId=b{rep}-{slug}-{n}', None, n)
                options = [s for s in ((again or {}).get('data') or []) if s.get('available')]
                if options:
                    rec.call('change', 'POST', f"/api/v1/customer/reservations/{row['id']}/change", {'requestId': f'{slug}-{rep}-m{n}', 'caseId': 7000 + n, 'branchId': branch, 'startsAt': random.Random(n * 13 + rep).choice(options)['startsAt']}, n)
        scheduled(offsets, session)

    def check(state, rows):
        created, changed = ok_count(rows, 'create'), ok_count(rows, 'change')
        wrong = consistency(state) + no_system(rows)
        if state['confirmed'] != created:
            wrong.append(f"confirmed {state['confirmed']} != created {created}")
        if state['canceled'] != changed:
            wrong.append(f"canceled {state['canceled']} != changed {changed}")
        if state['assignedEvents'] != created + changed:
            wrong.append(f"events {state['assignedEvents']}")
        return wrong
    measure(label, rep, {'sessions': sessions, 'arrivalPerSec': rate, 'arrival': 'Poisson open loop', 'thinkSec': '0.3-1.0', 'changeShare': .25, 'branches': 'staff 2/3/5'}, body, check)


def scenario_b(rep, customers, window):
    slot = SLOTS[(rep + customers) % len(SLOTS)]
    rng = random.Random(rep * 31 + customers)
    offsets = sorted(rng.uniform(0, window) for _ in range(customers))

    def body(rec):
        scheduled(offsets, lambda i: rec.call('create', 'POST', '/api/v1/customer/reservations', {'requestId': f'b-{customers}-{rep}-{i}', 'caseId': 7001 + i, 'branchId': 1, 'startsAt': iso(slot)}, i + 1))

    def check(state, rows):
        wrong = consistency(state) + no_system(rows)
        if state['confirmed'] != 2 or ok_count(rows, 'create') != 2:
            wrong.append(f"confirmed {state['confirmed']} ok {ok_count(rows, 'create')}")
        return wrong
    measure(f'B customers={customers}', rep, {'customers': customers, 'staff': 2, 'windowSec': window, 'arrival': 'uniform random in window'}, body, check)


def burst(rec, calls):
    gate = threading.Barrier(len(calls))
    def run(call):
        op, method, path, body, customer = call
        conn = http.client.HTTPConnection('127.0.0.1', PORT, timeout=120)
        conn.connect()
        gate.wait()
        rec.call(op, method, path, body, customer, conn)
    with ThreadPoolExecutor(max_workers=len(calls)) as pool:
        list(pool.map(run, calls))


def create_call(i, branch, slot, rid):
    return ('create', 'POST', '/api/v1/customer/reservations', {'requestId': rid, 'caseId': 7000 + i, 'branchId': branch, 'startsAt': iso(slot)}, i)


def scenario_l(rep, layout, n):
    calls = []
    for i in range(1, n + 1):
        rid = f'l-{layout}-{n}-{rep}-{i}'
        if layout == 'sameBranchSameSlot':
            calls.append(create_call(i, 11, FAR, rid))
        elif layout == 'sameBranchDiffSlot':
            calls.append(create_call(i, 11, SLOTS[i - 1], rid))
        else:
            calls.append(create_call(i, 20 + i, FAR, rid))

    def check(state, rows):
        wrong = consistency(state) + no_system(rows)
        if state['confirmed'] != n or ok_count(rows, 'create') != n:
            wrong.append(f"confirmed {state['confirmed']} ok {ok_count(rows, 'create')}")
        return wrong
    measure(f'L {layout} n={n}', rep, {'layout': layout, 'concurrent': n, 'hikariPoolDefault': 10}, lambda rec: burst(rec, calls), check)


def scenario_retry(rep):
    def body(rec):
        burst(rec, [create_call(1, 3, SLOTS[rep % len(SLOTS)], f'retry-{rep}') for _ in range(20)])
        for _ in range(5):
            rec.call('retry-sequential', 'POST', '/api/v1/customer/reservations', create_call(1, 3, SLOTS[rep % len(SLOTS)], f'retry-{rep}')[3], 1)

    def check(state, rows):
        wrong = consistency(state) + no_system(rows)
        if state['confirmed'] != 1 or state['assignedEvents'] != 1:
            wrong.append(f"confirmed {state['confirmed']} events {state['assignedEvents']}")
        if sum(200 <= r['status'] < 300 for r in rows) != 25 or len({r['id'] for r in rows}) != 1:
            wrong.append('not every retry got the same reservation')
        return wrong
    measure('C same requestId x20 + 5 sequential', rep, {'concurrent': 20, 'sequentialAfter': 5}, body, check)


def scenario_change(rep):
    a, b = SLOTS[0], SLOTS[1]
    staff = [100000 + sum(list(BRANCH_STAFF.values())[:list(BRANCH_STAFF).index(61)]) + k for k in range(1, 101)]
    held = ','.join(f"({9000+i},{7000+i},{1000+i},61,{staff[i-1]},'{utc(a)}','CONFIRMED')" for i in range(1, 21))
    block = ','.join(f"({9500+m},{7300+m},{1300+m},61,{staff[m-1]},'{utc(b)}','CONFIRMED')" for m in range(1, 99))
    preseed = f'insert into visit_reservation(id,case_id,customer_id,branch_id,assigned_staff_id,starts_at,status) values{held},{block};'

    def body(rec):
        burst(rec, [('change', 'POST', f'/api/v1/customer/reservations/{9000+i}/change', {'requestId': f'chg-{rep}-{i}', 'caseId': 7000 + i, 'branchId': 61, 'startsAt': iso(b)}, i) for i in range(1, 21)])

    def check(state, rows):
        wrong = consistency(state) + no_system(rows)
        kept = int(docker_sql(f"select count(*) from visit_reservation where id between 9001 and 9020 and status='CONFIRMED' and starts_at='{utc(a)}';"))
        if ok_count(rows, 'change') != 2 or kept != 18 or state['canceled'] != 2:
            wrong.append(f"ok {ok_count(rows, 'change')} kept {kept} canceled {state['canceled']}")
        return wrong
    measure('C concurrent change x20 free=2', rep, {'concurrent': 20, 'freeStaffAtTarget': 2}, body, check, preseed)


def scenario_extreme(rep, n, branch, staff):
    def body(rec):
        burst(rec, [create_call(i, branch, SLOTS[2], f'x-{n}-{staff}-{rep}-{i}') for i in range(1, n + 1)])

    def check(state, rows):
        wrong = consistency(state) + no_system(rows)
        expect = min(n, staff)
        if state['confirmed'] != expect or ok_count(rows, 'create') != expect:
            wrong.append(f"confirmed {state['confirmed']} ok {ok_count(rows, 'create')} expect {expect}")
        return wrong
    measure(f'C extreme n={n} staff={staff}', rep, {'concurrent': n, 'staff': staff, 'note': 'extreme contention, not representative of branch traffic'}, body, check)


seed()
login_all()
print('seeded; slots', len(SLOTS), 'first', iso(FAR), flush=True)
reset()
warm = Recorder()
scheduled([i * .04 for i in range(60)], lambda i: warm.call('warm', 'POST', '/api/v1/customer/reservations', {'requestId': f'warm-{i}', 'caseId': 7001 + i, 'branchId': 11 if i % 2 else 3, 'startsAt': iso(SLOTS[i % len(SLOTS)])}, i + 1))
for i in range(60):
    warm.call('warm', 'GET', f'/api/v1/customer/reservations?requestId=w{i}', None, i + 1)
print('warmup requests excluded', len(warm.rows), flush=True)
if os.environ.get('RES_DEBUG'):
    print(docker_sql("select name,enabled from setup_consumers where name like '%transactions%' or name like '%statements_history%'; select name,enabled,timed from setup_instruments where name='transaction'; select count(*) from events_transactions_history_long; select count(*),NESTING_EVENT_TYPE from events_statements_history_long where SQL_TEXT like 'select id from branch%' group by 2; select AUTOCOMMIT,STATE,count(*) from events_transactions_history_long group by 1,2; select THREAD_ID,EVENT_ID,NESTING_EVENT_ID,NESTING_EVENT_TYPE from events_statements_history_long where SQL_TEXT like 'select id from branch%' limit 2;", 'performance_schema')[:900], flush=True)
    raise SystemExit

for rep in range(1, REPS['A'] + 1):
    scenario_a('A normal 1 session/s', rep, 40 if not args.smoke else 8, 1.0)
    scenario_a('A busy 3 sessions/s', rep, 60 if not args.smoke else 12, 3.0)
for rep in range(1, REPS['B'] + 1):
    for customers, window in ((5, 2.0), (8, 2.0), (10, 2.0)):
        scenario_b(rep, customers, window)
for rep in range(1, REPS['L'] + 1):
    for n in (10, 20):
        for layout in ('sameBranchSameSlot', 'sameBranchDiffSlot', 'diffBranchSameSlot'):
            scenario_l(rep, layout, n)
for rep in range(1, REPS['C'] + 1):
    scenario_retry(rep)
    scenario_change(rep)
    for n, branch, staff in ((20, 62, 2), (50, 62, 2), (100, 62, 2), (20, 61, 100), (100, 61, 100)):
        scenario_extreme(rep, n, branch, staff)
print('DONE', len(RESULTS), 'runs; failed', sum(bool(r['failedExpectations']) for r in RESULTS), flush=True)
