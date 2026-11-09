import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { toUserMessageOr } from '../../api/errorMessages'
import { c02Api, taskBffEnabled, type BranchReservations, type C02CaseRow, type ReservationCandidate, type StaffReservation } from '../../api/taskApi'
import { caseNumber, formatDateTime, reservationNumber } from '../c02/c02CaseView'
import { needsReview, policyChangeApi } from '../../api/policyChangeApi'
import './staff-pages-v17.css'

const dateLabel = new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })
const pad2 = (value: number) => String(value).padStart(2, '0')
const timeLabel = (date: Date) => `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토']
const dayKey = (date: Date) => `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
const visitLabel = (iso: string) => { const d = new Date(iso); return `${pad2(d.getMonth() + 1)}.${pad2(d.getDate())}(${WEEKDAY[d.getDay()]}) ${timeLabel(d)}` }
const TASK_NAME = '상속예금 해지 및 지급 사전접수'

/** Fallback for opening a C-02 Case by number when it is not in the list. */
function CaseOpenCard() {
  const navigate = useNavigate()
  const [value, setValue] = useState('')
  const [invalid, setInvalid] = useState(false)
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const caseId = Number(value.trim())
    if (!Number.isSafeInteger(caseId) || caseId <= 0) { setInvalid(true); return }
    navigate(`/staff/c02/${caseId}`)
  }
  return <section className="v17-st-open" aria-labelledby="case-open-title">
    <h2 id="case-open-title">C-02 Case 바로 열기 <span>상속예금 사전접수 · Case 번호로 직원 검토 화면을 열어요</span></h2>
    <form onSubmit={submit} noValidate>
      <label htmlFor="staff-case-id">Case 번호</label>
      <input id="staff-case-id" inputMode="numeric" value={value} aria-invalid={invalid} aria-describedby={invalid ? 'staff-case-error' : undefined}
        onChange={event => { setValue(event.target.value); setInvalid(false) }} placeholder="예) 2" />
      <button type="submit">검토 화면 열기</button>
      {invalid && <span id="staff-case-error" role="alert">1 이상의 Case 번호를 입력해 주세요.</span>}
    </form>
  </section>
}

type Tone = { label: string; tone: string }
const CASE_STATUS: Record<string, Tone> = {
  SUBMITTED: { label: '검토 대기', tone: 'info' }, IN_REVIEW: { label: '검토 중', tone: 'progress' },
  REVIEW_PASSED: { label: '결과 등록 · 진행 가능', tone: 'success' }, REVIEW_REJECTED_REQUIREMENT: { label: '결과 등록 · 요건 미충족', tone: 'danger' },
  REVIEW_REJECTED_DOCUMENT: { label: '결과 등록 · 서류 부족', tone: 'danger' }, CANCELED: { label: '고객 취소', tone: 'muted' },
}
const REVIEW_STATUS: Record<string, Tone> = {
  PENDING: { label: '점검 대기', tone: 'muted' }, RUNNING: { label: '점검 중', tone: 'info' }, READY_FOR_STAFF: { label: '직원 검토 가능', tone: 'success' },
  WAITING_CUSTOMER: { label: '고객 확인 대기', tone: 'warn' }, FAILED_NEEDS_STAFF: { label: '직원 확인 필요', tone: 'danger' },
}
const RESERVATION_STATUS: Record<string, Tone> = {
  CONFIRMED: { label: '확정', tone: 'success' }, PENDING_ASSIGNMENT: { label: '배정 대기', tone: 'info' },
  SELECTED: { label: '선택됨', tone: 'muted' }, NEEDS_MANUAL_ASSIGNMENT: { label: '수동 배정 필요', tone: 'warn' },
  CANCELED: { label: '고객 취소', tone: 'muted' }, EXPIRED: { label: '만료', tone: 'muted' },
}
const needsAction = (row: C02CaseRow) => row.caseStatus === 'SUBMITTED' || row.caseStatus === 'IN_REVIEW'
const decided = (row: C02CaseRow) => row.caseStatus.startsWith('REVIEW_')
const flagged = (row: C02CaseRow) => needsAction(row) && (row.reviewStatus === 'FAILED_NEEDS_STAFF' || row.reviewStatus === 'WAITING_CUSTOMER')
// Order of attention: failed checks, customer answers, ready for staff, then the rest. A rule, not an AI ranking.
const PRIORITY: Record<string, number> = { FAILED_NEEDS_STAFF: 0, WAITING_CUSTOMER: 1, READY_FOR_STAFF: 2, RUNNING: 3, PENDING: 4 }
const byPriority = (a: C02CaseRow, b: C02CaseRow) => (PRIORITY[a.reviewStatus ?? ''] ?? 5) - (PRIORITY[b.reviewStatus ?? ''] ?? 5) || Date.parse(a.submittedAt ?? '') - Date.parse(b.submittedAt ?? '')
const bySubmitted = (a: C02CaseRow, b: C02CaseRow) => Date.parse(b.submittedAt ?? '') - Date.parse(a.submittedAt ?? '')

function Badge({ value, map, shape = 'pill' }: { value: string | null; map: Record<string, Tone>; shape?: 'pill' | 'tag' }) {
  const item = value ? map[value] : undefined
  return <span className={`v17-st-badge v17-st-badge--${item?.tone ?? 'muted'}${shape === 'tag' ? ' is-tag' : ''}`}>{item?.label ?? '—'}</span>
}
const RuleTag = () => <span className="v17-st-tag v17-st-tag--rule">규칙</span>
const AiTag = () => <span className="v17-st-tag v17-st-tag--ai">✦ AI</span>
const StaffTag = () => <span className="v17-st-tag v17-st-tag--staff">직원</span>

function useStaffCases() {
  const [rows, setRows] = useState<C02CaseRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (!taskBffEnabled) return
    let active = true
    c02Api.staffCases(`staff-cases-${Date.now()}`)
      .then(response => { if (active) setRows(response.data ?? []) })
      .catch(cause => { if (active) setError(toUserMessageOr(cause, '업무함을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.')) })
    return () => { active = false }
  }, [])
  return { rows, error }
}

function useBranchReservations() {
  const [data, setData] = useState<BranchReservations | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [version, setVersion] = useState(0)
  useEffect(() => {
    if (!taskBffEnabled) return
    let active = true
    c02Api.staffReservations(`staff-reservations-${Date.now()}`)
      .then(response => { if (active) setData(response.data) })
      .catch(cause => { if (active) setError(toUserMessageOr(cause, '예약 업무함을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.')) })
    return () => { active = false }
  }, [version])
  return { data, error, reload: () => setVersion(value => value + 1) }
}

/**
 * VISIT-08: the ranking failed or found no one, so a staff member of the same branch picks a colleague who is free at
 * that time. Spring re-checks branch, time, Case state and double booking under the row lock before confirming.
 */
function ManualAssignCard({ reservation, onAssigned }: { reservation: StaffReservation; onAssigned: () => void }) {
  const [candidates, setCandidates] = useState<ReservationCandidate[] | null>(null)
  const [choice, setChoice] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [version, setVersion] = useState(0)
  useEffect(() => {
    let active = true
    setCandidates(null); setChoice(null)
    c02Api.reservationCandidates(reservation.id, `staff-candidates-${reservation.id}-${Date.now()}`)
      .then(response => { if (active) setCandidates(response.data ?? []) })
      .catch(cause => { if (active) { setCandidates([]); setError(toUserMessageOr(cause, '배정 가능한 직원을 불러오지 못했어요.')) } })
    return () => { active = false }
  }, [reservation.id, version])
  const assign = async () => {
    if (choice === null) return
    setBusy(true); setError(null)
    try { await c02Api.assignReservation(reservation.id, { staffId: choice, requestId: `staff-assign-${reservation.id}-${crypto.randomUUID().slice(0, 18)}` }); onAssigned() }
    catch (cause) { setError(toUserMessageOr(cause, '배정하지 못했어요. 그 사이 다른 예약이 잡혔을 수 있어요. 후보를 다시 불러왔어요.')); setVersion(value => value + 1) }
    finally { setBusy(false) }
  }
  return <section className="v17-st-card v17-st-manual" aria-label="수동 배정">
    <h2><span className="v17-st-tag v17-st-tag--rule">직원</span>담당 직원 직접 배정</h2>
    <p>자동 배정이 되지 않은 예약이에요. 이 시간에 비어 있는 같은 지점 직원 중에서 골라 주세요. 현재 배정 업무량이 적은 순서로 보여요.</p>
    {error && <p className="v17-st-alert" role="alert">{error}</p>}
    {candidates === null ? <p className="v17-st-none">후보를 불러오고 있어요.</p>
      : candidates.length === 0 ? <p className="v17-st-agent__note">이 시간에 비어 있는 직원이 없어요. 고객에게 다른 시간으로 변경을 안내해 주세요. 방문 시간까지 배정되지 않으면 예약은 만료돼요.</p>
        : <ul className="v17-st-pick">{candidates.map(row => <li key={row.staffId}><label className={choice === row.staffId ? 'is-on' : undefined}>
          <input type="radio" name={`assign-${reservation.id}`} checked={choice === row.staffId} onChange={() => setChoice(row.staffId)} />
          <span>{row.rank}</span><strong>{row.name ?? `직원 #${row.staffId}`}</strong><span className="v17-st-mono is-s">현재 배정 {row.activeAssignments}건</span></label></li>)}</ul>}
    <button type="button" className="v17-st-btn v17-st-btn--primary" disabled={choice === null || busy} onClick={() => void assign()}>{busy ? '배정 중…' : '이 직원으로 배정'}</button>
  </section>
}

const reasonOf = (row: C02CaseRow) => row.reviewStatus === 'FAILED_NEEDS_STAFF' ? 'AI 사전점검이 끝나지 못했어요 → 원본 확인 필요'
  : row.reviewStatus === 'WAITING_CUSTOMER' ? '고객의 추가 확인 답변을 기다리고 있어요'
    : row.reviewStatus === 'READY_FOR_STAFF' ? (row.caseStatus === 'IN_REVIEW' ? '검토 중 · 결과 등록이 남았어요' : '사전점검이 끝났어요 → 검토를 시작할 수 있어요')
      : 'AI 사전점검이 진행 중이에요'

function Calendar({ rows, reservations }: { rows: C02CaseRow[]; reservations: StaffReservation[] }) {
  const today = new Date()
  const [month, setMonth] = useState(new Date(today.getFullYear(), today.getMonth(), 1))
  const [picked, setPicked] = useState(dayKey(today))
  const counts = useMemo(() => {
    const map = new Map<string, { recv: number; done: number; visit: number }>()
    const bump = (iso: string | null, key: 'recv' | 'done' | 'visit') => {
      if (!iso) return
      const k = dayKey(new Date(iso)); const entry = map.get(k) ?? { recv: 0, done: 0, visit: 0 }; entry[key] += 1; map.set(k, entry)
    }
    rows.forEach(row => { bump(row.submittedAt, 'recv'); bump(row.decidedAt, 'done') })
    reservations.forEach(item => bump(item.startsAt, 'visit'))
    return map
  }, [rows, reservations])
  const first = month.getDay()
  const days = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate()
  const cells = [...Array.from({ length: first }, () => null), ...Array.from({ length: days }, (_, index) => new Date(month.getFullYear(), month.getMonth(), index + 1))]
  const pickedCount = counts.get(picked) ?? { recv: 0, done: 0, visit: 0 }
  const pickedDate = new Date(`${picked}T00:00:00`)
  return <section className="v17-st-card v17-st-cal">
    <div className="v17-st-cal__head"><h2>업무 캘린더</h2><div><button type="button" aria-label="이전 달" onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() - 1, 1))}>‹</button><strong>{month.getFullYear()}.{pad2(month.getMonth() + 1)}</strong><button type="button" aria-label="다음 달" onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() + 1, 1))}>›</button></div></div>
    <div className="v17-st-cal__legend"><span className="is-recv">접수</span><span className="is-done">결과 등록</span><span className="is-visit">방문 예약</span></div>
    <div className="v17-st-cal__grid" role="grid">
      {WEEKDAY.map((name, index) => <span key={name} className={`v17-st-cal__wd${index === 0 ? ' is-sun' : index === 6 ? ' is-sat' : ''}`}>{name}</span>)}
      {cells.map((date, index) => { if (!date) return <span key={`blank-${index}`} />; const key = dayKey(date); const c = counts.get(key); const isToday = key === dayKey(today)
        return <button type="button" key={key} className={`v17-st-cal__day${key === picked ? ' is-on' : ''}${isToday ? ' is-today' : ''}${date.getDay() === 0 ? ' is-sun' : date.getDay() === 6 ? ' is-sat' : ''}`} onClick={() => setPicked(key)}>
          <strong>{date.getDate()}</strong>{c?.recv ? <span className="is-recv">{c.recv}</span> : null}{c?.done ? <span className="is-done">{c.done}</span> : null}{c?.visit ? <span className="is-visit">{c.visit}</span> : null}
        </button> })}
    </div>
    <div className="v17-st-cal__day-sum"><strong>{pad2(pickedDate.getMonth() + 1)}월 {pickedDate.getDate()}일 ({WEEKDAY[pickedDate.getDay()]}){picked === dayKey(today) ? ' · 오늘' : ''}</strong>
      <div><span>접수 <b className="is-recv">{pickedCount.recv}건</b></span><span>결과 등록 <b className="is-done">{pickedCount.done}건</b></span><span>방문 예약 <b className="is-visit">{pickedCount.visit}건</b></span></div>
      <div><Link to="/staff/queue">접수 보기</Link><Link to="/staff/branch">예약 보기</Link></div>
    </div>
  </section>
}

/** StaffDashboard: counts and lists come from the same read-only lists as the two inboxes. */
/** Pending bank policy changes; null when this account has no review grant (the API refuses it). */
function usePendingPolicyChanges() {
  const [pending, setPending] = useState<{ count: number; latest: string | null } | null>(null)
  useEffect(() => {
    if (!taskBffEnabled) return
    let active = true
    policyChangeApi.overview()
      .then(response => { if (!active) return; const rows = (response.data?.changes ?? []).filter(needsReview); setPending({ count: rows.length, latest: rows[0]?.change.changeSummary ?? null }) })
      .catch(() => { if (active) setPending(null) })
    return () => { active = false }
  }, [])
  return pending
}

export function StaffDashboardPage() {
  const { user } = useAuth()
  const policy = usePendingPolicyChanges()
  const { rows, error } = useStaffCases()
  const branch = useBranchReservations()
  const open = (rows ?? []).filter(needsAction)
  const count = (pred: (row: C02CaseRow) => boolean) => open.filter(pred).length
  const branchName = branch.data?.branch.name ?? '지점'
  const now = new Date()
  const tiles = [
    { axis: '처리 상태', ai: false, label: '검토 대기', n: count(row => row.caseStatus === 'SUBMITTED'), sub: '검토 시작 전 · 고객 취소 가능', tone: 'plain' },
    { axis: '처리 상태', ai: false, label: '검토 중', n: count(row => row.caseStatus === 'IN_REVIEW'), sub: 'IN_REVIEW', tone: 'blue' },
    { axis: 'AI 사전점검', ai: true, label: 'AI 점검 중', n: count(row => row.reviewStatus === 'PENDING' || row.reviewStatus === 'RUNNING'), sub: 'PENDING · RUNNING', tone: 'blue' },
    { axis: 'AI 사전점검', ai: true, label: '고객 확인 대기', n: count(row => row.reviewStatus === 'WAITING_CUSTOMER'), sub: 'WAITING_CUSTOMER', tone: 'warn' },
    { axis: 'AI 사전점검', ai: true, label: '직원 검토 가능', n: count(row => row.reviewStatus === 'READY_FOR_STAFF'), sub: 'READY_FOR_STAFF', tone: 'green' },
    { axis: 'AI 사전점검', ai: true, label: '직원 확인 필요', n: count(row => row.reviewStatus === 'FAILED_NEEDS_STAFF'), sub: 'FAILED_NEEDS_STAFF · 추정 통과 없음', tone: 'alert' },
  ]
  const first = [...open].sort(byPriority).slice(0, 4)
  const canceled = (rows ?? []).filter(row => row.caseStatus === 'CANCELED').length
  const reservations = branch.data?.reservations ?? []
  const todayKey = dayKey(now)
  const todayVisits = reservations.filter(item => dayKey(new Date(item.startsAt)) === todayKey)
  const weekEnd = new Date(now); weekEnd.setDate(now.getDate() + 7)
  const weekConfirmed = reservations.filter(item => item.status === 'CONFIRMED' && new Date(item.startsAt) < weekEnd)
  const manual = reservations.filter(item => item.status === 'NEEDS_MANUAL_ASSIGNMENT')
  return <div className="v17-st">
    <div className="v17-st-head v17-st-head--row">
      <div><h1>{user?.name ?? '직원'}님, 오늘도 힘찬 하루 보내세요!</h1><span>{dateLabel.format(now)} · 기준 시각 {timeLabel(now)} · {user?.role === 'ADMIN' ? '관리자' : '직원'}{branch.data ? ` · ${branchName}` : ''}</span></div>
      <div className="v17-st-head__actions"><Link to="/staff/queue" className="v17-st-btn v17-st-btn--primary">사전검토 업무함</Link><Link to="/staff/branch" className="v17-st-btn v17-st-btn--line">{branchName} 예약 업무함</Link></div>
    </div>
    {error && <p className="v17-st-alert" role="alert">{error}</p>}
    <section className="v17-st-sec">
      <div className="v17-st-sec__title"><h2>사전검토 업무함 현황</h2><span>예약 전 C업무 · 처리 상태와 AI 사전점검 상태를 따로 집계해요</span></div>
      <div className="v17-st-tiles">{tiles.map(tile => <Link key={tile.label} to="/staff/queue" className={`v17-st-tile is-${tile.tone}`}>
        <span className="v17-st-tile__axis">{tile.ai && <AiTag />}{tile.axis}</span><span className="v17-st-tile__label">{tile.label}</span>
        <span className="v17-st-tile__n">{rows ? tile.n : '—'}<span> 건</span></span><span className="v17-st-tile__sub">{tile.sub}</span>
      </Link>)}</div>
      <div className="v17-st-flags">{count(flagged) > 0 && <span className="is-warn">플래그 · 확인 필요 {count(flagged)}</span>}<span className="is-muted">고객 취소 {canceled} · 검토 시작 전 취소</span>
        {policy && <Link to="/staff/policy-changes" className={policy.count > 0 ? 'is-warn' : 'is-muted'} title={policy.latest ?? undefined}>업무기준 변경 검토 대기 {policy.count}건{policy.count > 0 ? ' ›' : ''}</Link>}</div>
    </section>
    <div className="v17-st-cols">
      <div className="v17-st-cols__main">
        <section className="v17-st-brief">
          <div className="v17-st-brief__head"><RuleTag /><h2>오늘 먼저 볼 Case</h2><span className="v17-st-brief__live">처리 필요 {open.length}건</span><span className="v17-st-brief__asof">{timeLabel(now)} 기준 · 점검 상태 순서로 정리했어요</span></div>
          <p>오늘 먼저 볼 건은 <strong>{first.length}건</strong>이에요. 사전점검에 실패한 건, 고객 답변을 기다리는 건, 검토를 시작할 수 있는 건 순서로 정리했어요.</p>
          {rows && first.length === 0 && <p className="v17-st-empty">지금 처리할 Case가 없어요.</p>}
          <ul>{first.map((row, index) => <li key={row.caseId} className={row.reviewStatus === 'FAILED_NEEDS_STAFF' ? 'is-alert' : row.reviewStatus === 'WAITING_CUSTOMER' ? 'is-warn' : undefined}>
            <span className="v17-st-brief__rank">{index + 1}</span>
            <div><div><span className="v17-st-code">C-02</span><strong>{TASK_NAME}</strong><span className="v17-st-mono">{caseNumber(row.caseId, row.createdAt)}</span><span>· {row.customerName}</span></div>
              <span className="v17-st-brief__why"><RuleTag />{reasonOf(row)}</span>
              <span className="v17-st-brief__meta">접수 {formatDateTime(row.submittedAt)} · {CASE_STATUS[row.caseStatus]?.label ?? row.caseStatus} · {row.reviewStatus ?? '—'}</span></div>
            <Link to={`/staff/c02/${row.caseId}`} className="v17-st-btn v17-st-btn--line">업무 상세보기</Link>
          </li>)}</ul>
          <div className="v17-st-legend"><span><RuleTag />정해진 기준으로 판정</span><span><AiTag />점검·정리 (참고용)</span><span><StaffTag />직원이 확인·확정</span><span className="v17-st-legend__note">처리 순서와 결과는 직원이 정해요.</span></div>
        </section>
        <section className="v17-st-card">
          <div className="v17-st-card__title"><div><h2>{branchName} 방문 예약</h2><span>예약 후 Case는 소속 지점 업무함에서만 열람해요</span></div><Link to="/staff/branch">예약 업무함 ›</Link></div>
          {branch.error && <p className="v17-st-alert" role="alert">{branch.error}</p>}
          <div className="v17-st-mini">
            <div><span>오늘 방문 예정</span><strong>{todayVisits.length}건</strong><span>{todayVisits.map(item => `${timeLabel(new Date(item.startsAt))} ${item.customerName}`).join(' · ') || '예정 없음'}</span></div>
            <div><span>이번 주 확정 예약</span><strong>{weekConfirmed.length}건</strong><span>CONFIRMED</span></div>
            <div className="is-warn"><span>수동 배정 필요</span><strong>{manual.length}건</strong><span>{manual.map(item => `${visitLabel(item.startsAt)} ${item.customerName}`).join(' · ') || '없음'}</span></div>
          </div>
        </section>
      </div>
      <Calendar rows={rows ?? []} reservations={reservations} />
    </div>
    <CaseOpenCard />
  </div>
}

const TABS = [
  { key: 'action', label: '처리 필요', filter: needsAction },
  { key: 'submitted', label: '검토 대기', filter: (row: C02CaseRow) => row.caseStatus === 'SUBMITTED' },
  { key: 'review', label: '검토 중', filter: (row: C02CaseRow) => row.caseStatus === 'IN_REVIEW' },
  { key: 'done', label: '결과 등록', filter: decided },
  { key: 'canceled', label: '고객 취소', filter: (row: C02CaseRow) => row.caseStatus === 'CANCELED' },
  { key: 'all', label: '전체', filter: (_row: C02CaseRow) => true },
] as const
type TabKey = (typeof TABS)[number]['key']

/** StaffList. The list API carries status, times and the customer name; columns it does not carry are not shown. */
export function StaffQueuePage() {
  const { rows, error } = useStaffCases()
  const [tab, setTab] = useState<TabKey>('action')
  const [query, setQuery] = useState('')
  const [ai, setAi] = useState('all')
  const [onlyFlags, setOnlyFlags] = useState(false)
  const [sort, setSort] = useState<'recent' | 'priority'>('recent')
  const now = new Date()
  const current = TABS.find(item => item.key === tab) ?? TABS[0]
  const shown = (rows ?? []).filter(current.filter)
    .filter(row => !query.trim() || `${caseNumber(row.caseId, row.createdAt)} ${row.customerName} ${row.caseId}`.includes(query.trim()))
    .filter(row => ai === 'all' || row.reviewStatus === ai)
    .filter(row => !onlyFlags || flagged(row))
    .sort(sort === 'recent' ? bySubmitted : byPriority)
  return <div className="v17-st">
    <div className="v17-st-head"><h1>사전검토 업무함</h1><span>고객이 제출한 C업무 중 <strong>방문 예약 전</strong> Case만 보여요. 예약이 확정된 Case는 해당 지점 예약 업무함으로 넘어가요. 임시저장 중인 업무는 나타나지 않아요. · 기준 {pad2(now.getMonth() + 1)}.{pad2(now.getDate())}({WEEKDAY[now.getDay()]}) {timeLabel(now)}</span></div>
    {error && <p className="v17-st-alert" role="alert">{error}</p>}
    <div className="v17-st-tabs" role="tablist">{TABS.map(item => <button type="button" role="tab" key={item.key} aria-selected={tab === item.key} className={tab === item.key ? 'is-on' : undefined} onClick={() => setTab(item.key)}>{item.label} <span>{rows ? rows.filter(item.filter).length : ''}</span></button>)}</div>
    <section className="v17-st-filter">
      <div className="is-grow"><label htmlFor="v17-st-q">접수번호·고객명</label><input id="v17-st-q" value={query} onChange={event => setQuery(event.target.value)} placeholder="C-2026… 또는 고객명" /></div>
      <div className="is-task"><label htmlFor="v17-st-task">업무</label><select id="v17-st-task" defaultValue="C-02"><option value="C-02">C-02 상속예금</option></select></div>
      <div className="is-ai"><label htmlFor="v17-st-ai">AI 사전점검 상태</label><select id="v17-st-ai" value={ai} onChange={event => setAi(event.target.value)}><option value="all">전체</option>{Object.entries(REVIEW_STATUS).map(([key, item]) => <option key={key} value={key}>{item.label}</option>)}</select></div>
      <label className={`v17-st-filter__flag${onlyFlags ? ' is-on' : ''}`}><input type="checkbox" checked={onlyFlags} onChange={event => setOnlyFlags(event.target.checked)} />플래그 있는 건만</label>
      <div className="v17-st-filter__sort"><span>정렬</span><span><button type="button" className={sort === 'recent' ? 'is-on' : undefined} aria-pressed={sort === 'recent'} onClick={() => setSort('recent')}>접수 최신순</button><button type="button" className={sort === 'priority' ? 'is-on' : undefined} aria-pressed={sort === 'priority'} onClick={() => setSort('priority')}><RuleTag />점검 우선순</button></span></div>
    </section>
    <section className="v17-st-table-card">
      <div className="v17-st-scroll"><table className="v17-st-table">
        <thead><tr><th>접수번호</th><th>업무 · 고객</th><th>접수일시</th><th>처리 상태</th><th><span className="v17-st-th-ai"><AiTag />사전점검</span></th><th className="is-staffcol">직원 확인 항목</th><th className="is-last">검토자</th></tr></thead>
        <tbody>{shown.map(row => <tr key={row.caseId} className={flagged(row) ? 'is-flag' : undefined}>
          <td className="is-first"><Link to={`/staff/c02/${row.caseId}`} className={row.caseStatus === 'IN_REVIEW' ? 'is-mine' : undefined}>{caseNumber(row.caseId, row.createdAt)}</Link></td>
          <td><span className="v17-st-who"><span><span className="v17-st-code is-s">C-02</span><strong>{row.customerName}</strong></span><span>{TASK_NAME}</span></span></td>
          <td className="is-date">{formatDateTime(row.submittedAt)}</td>
          <td><span className="v17-st-cell"><Badge value={row.caseStatus} map={CASE_STATUS} />{row.caseStatus === 'SUBMITTED' && <span>검토 시작 전</span>}</span></td>
          <td>{needsAction(row) ? <span className="v17-st-cell"><Badge value={row.reviewStatus} map={REVIEW_STATUS} shape="tag" /><span className="v17-st-mono is-s">{row.reviewStatus ?? ''}</span></span> : '—'}</td>
          <td><span className="v17-st-cell"><strong className={row.staffSupplement ? 'is-sup' : undefined}>{row.submittedDocuments ? `${(row.staffConfirmed ?? 0) + (row.staffSupplement ?? 0)} / ${row.submittedDocuments}건` : '—'}</strong>
            <span>{row.submittedDocuments ? `확인 ${row.staffConfirmed ?? 0} · 보완 ${row.staffSupplement ?? 0} · 미확인 ${row.staffUnreviewed ?? 0}` : '제출 파일 없음'}</span>
            {row.lastReviewedAt && <span>마지막 확인 {formatDateTime(row.lastReviewedAt)}</span>}</span></td>
          <td className="is-last">{row.reviewerName ? <span className="v17-st-cell"><span className="is-name">{row.reviewerName}</span><span>{row.reviewStartedAt ? `${formatDateTime(row.reviewStartedAt)} 시작` : ''}</span></span> : '미배정'}{row.decidedAt && <span className="v17-st-cell"><span>{formatDateTime(row.decidedAt)} 결과 등록</span></span>}</td>
        </tr>)}</tbody>
      </table></div>
      {rows && shown.length === 0 && <p className="v17-st-none">해당하는 Case가 없어요.</p>}
      {!rows && !error && <p className="v17-st-none">불러오는 중이에요.</p>}
      <div className="v17-st-foot">처리 상태와 AI 사전점검 상태는 서로 다른 축이에요. AI가 ‘직원 검토 가능’으로 표시해도 직원 검토를 생략하지 않아요. 고객 취소는 직원 검토 시작 전에만 가능해요. 업무함 열람 범위는 팀 결정 전이라, 시연에서는 사전검토 권한이 있는 직원에게만 보여요.
        <div>직원 확인 항목은 직원이 원본을 보고 저장한 서류 판단이에요. OCR 자동 대조 일치는 여기에 세지 않아요. 검토자는 [검토 시작]을 누른 직원이며, 시작 전에는 미배정이에요.</div>
        <div className="v17-st-legend"><span><RuleTag />정해진 기준으로 판정 (처리 상태, 점검 우선순)</span><span><AiTag />점검·정리 (참고용)</span><span><StaffTag />직원이 확인·확정</span></div></div>
    </section>
    <CaseOpenCard />
  </div>
}

const BRANCH_FILTERS = [
  { key: 'all', label: '전체', test: (_item: StaffReservation) => true },
  { key: 'confirmed', label: '확정', test: (item: StaffReservation) => item.status === 'CONFIRMED' },
  { key: 'pending', label: '배정 대기', test: (item: StaffReservation) => item.status === 'PENDING_ASSIGNMENT' || item.status === 'SELECTED' },
  { key: 'manual', label: '수동 배정 필요', test: (item: StaffReservation) => item.status === 'NEEDS_MANUAL_ASSIGNMENT' },
  { key: 'closed', label: '취소·만료', test: (item: StaffReservation) => item.status === 'CANCELED' || item.status === 'EXPIRED' },
] as const

/** StaffBranch: the branch's own bookings; the detail panel shows what the list API carries. */
export function StaffBranchPage() {
  const { user } = useAuth()
  const { data, error, reload } = useBranchReservations()
  const [filter, setFilter] = useState<(typeof BRANCH_FILTERS)[number]['key']>('all')
  const [selected, setSelected] = useState<number | null>(null)
  const items = data?.reservations ?? []
  const shown = items.filter(BRANCH_FILTERS.find(entry => entry.key === filter)?.test ?? (() => true))
  const pick = shown.find(item => item.id === selected) ?? shown[0] ?? null
  const now = new Date()
  const name = data?.branch.name ?? '지점'
  return <div className="v17-st">
    <div className="v17-st-head"><h1>{name} 예약 업무함</h1><span>직원 검토 결과 <strong>진행 가능</strong> 통지 뒤 고객이 예약한 Case만 보여요. 소속 지점({name}) 예약만 열람할 수 있고, 다른 지점 예약은 보이지 않아요. · 기준 {pad2(now.getMonth() + 1)}.{pad2(now.getDate())}({WEEKDAY[now.getDay()]}) {timeLabel(now)}</span></div>
    {error && <p className="v17-st-alert" role="alert">{error}</p>}
    <div className="v17-st-pills">{BRANCH_FILTERS.map(entry => <button type="button" key={entry.key} className={filter === entry.key ? 'is-on' : undefined} aria-pressed={filter === entry.key} onClick={() => setFilter(entry.key)}>{entry.label} {items.filter(entry.test).length}</button>)}</div>
    <div className="v17-st-cols">
      <section className="v17-st-table-card v17-st-cols__main">
        {!data && !error && <p className="v17-st-none">불러오는 중이에요.</p>}
        {data && shown.length === 0 && <p className="v17-st-none">예정된 방문 예약이 없어요.</p>}
        {shown.length > 0 && <div className="v17-st-scroll"><table className="v17-st-table v17-st-table--branch">
          <thead><tr><th>방문 일시</th><th>고객 · Case</th><th>예약 상태</th><th className="is-last">담당 직원</th></tr></thead>
          <tbody>{shown.map(item => <tr key={item.id} className={item.id === pick?.id ? 'is-picked' : undefined} onClick={() => setSelected(item.id)}>
            <td className="is-first"><button type="button" className="v17-st-rowbtn" onClick={() => setSelected(item.id)}>{visitLabel(item.startsAt)}</button></td>
            <td><span className="v17-st-who"><strong>{item.customerName}</strong><span className="v17-st-mono is-s">C-02 · Case #{item.caseId}</span></span></td>
            <td><span className="v17-st-cell"><Badge value={item.status} map={RESERVATION_STATUS} /><span className="v17-st-mono is-s">{item.status}</span></span></td>
            <td className={`is-last${item.assignedStaffName ? '' : ' is-warn'}`}>{item.assignedStaffName ? `${item.assignedStaffName}${item.assignedStaffId === user?.userId ? ' (나)' : ''}` : '미배정'}</td>
          </tr>)}</tbody>
        </table></div>}
        <div className="v17-st-foot">취소·만료된 예약은 정원과 직원 가용량을 이미 반환했어요. 고객에게 만료·취소된 예약으로 방문하라고 안내하지 않아요.</div>
      </section>
      <aside className="v17-st-side">
        {pick ? <section className="v17-st-card v17-st-detail">
          <h2>{pick.customerName} · {visitLabel(pick.startsAt)} <Badge value={pick.status} map={RESERVATION_STATUS} /></h2>
          <dl><dt>예약번호</dt><dd className="v17-st-mono">{reservationNumber(pick.id)}</dd><dt>Case</dt><dd>Case #{pick.caseId} · {TASK_NAME}</dd><dt>담당 직원</dt><dd><strong>{pick.assignedStaffName ?? '미배정'}</strong></dd></dl>
          <Link to={`/staff/c02/${pick.caseId}`}>사전검토 결과·방문 준비물 보기 ›</Link>
        </section> : <section className="v17-st-card"><p className="v17-st-none">예약을 고르면 상세가 보여요.</p></section>}
        {pick?.status === 'NEEDS_MANUAL_ASSIGNMENT' && <ManualAssignCard reservation={pick} onAssigned={reload} />}
        <section className="v17-st-card v17-st-agent">
          <h2><span className="v17-st-tag v17-st-tag--rule">규칙</span>담당 직원 추천·배정 근거</h2>
          <p>Spring이 <strong>소속 지점·방문 시간 중복</strong>으로 후보를 거른 뒤, 추천 순위는 <strong>현재 배정 업무량</strong>이 적은 순서예요. LLM이나 업무 경험·전문성 점수는 쓰지 않아요.</p>
          {pick?.assignment ? <>
            <dl className="v17-st-assign"><dt>배정 시각</dt><dd>{pick.assignment.assignedAt ? formatDateTime(pick.assignment.assignedAt) : '—'}</dd><dt>후보 수</dt><dd>{pick.assignment.candidateCount}명</dd>
              <dt>추천 1순위</dt><dd>{pick.assignment.recommendedStaffName ?? '추천 없음'}</dd><dt>최종 배정</dt><dd><strong>{pick.assignment.assignedStaffName ?? '미배정'}</strong> · 배정 당시 업무량 {pick.assignment.assignedWorkload}건</dd></dl>
            {pick.assignment.basis === 'MANUAL'
              ? <p className="v17-st-agent__note">자동 배정이 되지 않아 지점 직원이 직접 배정했어요. 아래 순위는 그때 비어 있던 직원의 업무량 순서예요.</p>
              : pick.assignment.recommendedStaffId != null && pick.assignment.assignedStaffId != null && pick.assignment.recommendedStaffId !== pick.assignment.assignedStaffId && <p className="v17-st-agent__note">추천 1순위가 같은 시간에 먼저 배정돼, 순위상 다음 직원으로 배정했어요.</p>}
            {pick.assignment.ranked ? <ol className="v17-st-ranked">{pick.assignment.ranked.map(row => <li key={row.staffId} className={row.staffId === pick.assignment?.assignedStaffId ? 'is-on' : undefined}><span>{row.rank}</span><span><strong>{row.name ?? `직원 #${row.staffId}`}</strong><span>배정 당시 업무량 {row.activeAssignments}건</span></span>{row.staffId === pick.assignment?.assignedStaffId && <em>배정</em>}</li>)}</ol>
              : <p className="v17-st-agent__note">이 예약은 후보별 순위를 저장하기 전에 만들어져 전체 후보 목록이 없어요. 위의 배정 기록만 남아 있어요.</p>}
          </> : pick && <p className="v17-st-agent__note">이 예약의 배정 기록을 찾지 못했어요.</p>}
          <p className="v17-st-agent__note">Spring 재검증 통과 후에만 확정해요. 추천은 순위만 제안하고 배정을 직접 확정하지 않아요.</p>
        </section>
        {pick?.events && pick.events.length > 0 && <section className="v17-st-card v17-st-events"><h2>예약·배정 이력 <span>감사 이벤트</span></h2>
          <ol>{pick.events.map((event, index) => <li key={index}><span className="v17-st-mono">{formatDateTime(event.at)}</span><span className={`v17-st-actor is-${event.actorType.toLowerCase()}`}>{event.actorType === 'SYSTEM' ? 'Spring' : event.actorType === 'CUSTOMER' ? '고객' : event.actorType === 'STAFF' ? '직원' : event.actorType}</span><span>{event.eventType === 'VISIT_ASSIGNED' ? `담당 직원 배정 → ${event.toValue === String(pick.assignment?.assignedStaffId) ? pick.assignment?.assignedStaffName ?? '' : `직원 #${event.toValue}`}` : event.eventType === 'VISIT_CANCELED' ? '예약 취소' : event.eventType}</span></li>)}</ol></section>}
      </aside>
    </div>
  </div>
}
