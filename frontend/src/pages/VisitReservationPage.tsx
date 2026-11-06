import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { toUserMessageOr } from '../api/errorMessages'
import { c02Api, taskBffEnabled, type Branch, type C02CaseRow, type C02CustomerCaseSnapshot, type Reservation } from '../api/taskApi'
import { caseNumber, formatDateTime } from '../features/c02/c02CaseView'
import '../features/t2/customer-pages.css'
import '../features/c02/c02-case.css'

// Demo-only opening hours. branch_slot is not in the Flyway schema yet, so no remaining-seat counts are shown.
const HOURS = [10, 11, 13, 14, 15]
const LOCK_MS = 24 * 60 * 60 * 1000
const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토']
const pad2 = (value: number) => String(value).padStart(2, '0')
const dayKey = (date: Date) => `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
const dayLabel = (date: Date) => `${pad2(date.getMonth() + 1)}.${pad2(date.getDate())}(${WEEKDAY[date.getDay()]})`

function nextDays(count: number): Date[] {
  const today = new Date(); today.setHours(0, 0, 0, 0)
  return Array.from({ length: count }, (_, index) => { const day = new Date(today); day.setDate(today.getDate() + index + 1); return day })
}
const slotTime = (day: Date, hour: number) => { const at = new Date(day); at.setHours(hour, 0, 0, 0); return at }
const isWeekend = (day: Date) => day.getDay() === 0 || day.getDay() === 6
/** A visit within 24 hours could not be changed or cancelled any more, so it is not offered. */
const bookable = (at: Date) => at.getTime() - Date.now() > LOCK_MS

/** Customer visit booking after a notified PROCEED. Spring re-checks eligibility and assigns the staff member. */
export function VisitReservationPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const caseId = Number(params.get('caseId'))
  const hasCase = Number.isSafeInteger(caseId) && caseId > 0
  const [snapshot, setSnapshot] = useState<C02CustomerCaseSnapshot | null>(null)
  const [cases, setCases] = useState<C02CaseRow[] | null>(null)
  const [branches, setBranches] = useState<Branch[]>([])
  const [existing, setExisting] = useState<Reservation | null>(null)
  const [branchId, setBranchId] = useState<number | null>(null)
  const days = useMemo(() => nextDays(7), [])
  const [day, setDay] = useState<string | null>(null)
  const [hour, setHour] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!taskBffEnabled) return
    let active = true
    const load = async () => {
      try {
        if (!hasCase) { const rows = await c02Api.customerCases(`reservation-cases-${Date.now()}`); if (active) setCases(rows.data ?? []); return }
        const [snap, branchRows, reservations] = await Promise.all([
          c02Api.snapshot(caseId, `reservation-snapshot-${Date.now()}`),
          c02Api.branches(`reservation-branches-${Date.now()}`),
          c02Api.reservations(`reservation-list-${Date.now()}`),
        ])
        if (!active) return
        setSnapshot(snap.data); setBranches(branchRows.data ?? [])
        setExisting(reservations.data?.find(item => item.caseId === caseId && item.status !== 'CANCELED') ?? null)
        if (branchRows.data?.length === 1) setBranchId(branchRows.data[0].id)
      } catch (cause) { if (active) setError(toUserMessageOr(cause, '예약 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.')) }
    }
    void load()
    return () => { active = false }
  }, [caseId, hasCase])

  const selectedDay = days.find(item => dayKey(item) === day) ?? null
  const selectedAt = selectedDay && hour !== null ? slotTime(selectedDay, hour) : null
  const branch = branches.find(item => item.id === branchId) ?? null
  const detailHref = `/tasks/c02/cases/${caseId}`

  const reserve = async () => {
    if (!branch || !selectedAt || !snapshot) return
    setBusy(true); setError(null)
    try {
      await c02Api.createReservation({ requestId: `reservation-create-${caseId}-${crypto.randomUUID()}`, caseId, branchId: branch.id, staffId: null, startsAt: selectedAt.toISOString() })
      navigate(detailHref)
    } catch (cause) { setError(toUserMessageOr(cause, '예약을 요청하지 못했어요. 잠시 후 다시 시도해 주세요.')) } finally { setBusy(false) }
  }
  const cancel = async () => {
    if (!existing) return
    setBusy(true); setError(null)
    try { await c02Api.cancelReservation(existing.id, `reservation-cancel-${existing.id}-${crypto.randomUUID()}`); setExisting(null) }
    catch (cause) { setError(toUserMessageOr(cause, '예약을 취소하지 못했어요. 잠시 후 다시 시도해 주세요.')) } finally { setBusy(false) }
  }

  if (!hasCase) return <main className="c02-detail">
    <h1 className="c02-reserve__title">방문 영업점 예약</h1>
    {error && <p className="c02-alert" role="alert">{error}</p>}
    <section className="c02-detail__card"><h2>예약할 접수 선택</h2>
      {cases === null ? <p className="c02-notice" role="status">접수 내역을 불러오고 있어요.</p>
        : cases.filter(row => row.caseStatus === 'REVIEW_PASSED').length === 0 ? <p className="c02-notice">직원 검토 결과가 ‘진행 가능’인 접수가 없어요. 결과 알림을 받은 뒤 예약할 수 있어요.</p>
          : <ul className="c02-case-list">{cases.filter(row => row.caseStatus === 'REVIEW_PASSED').map(row => <li key={row.caseId}><Link to={`/tasks/c02/reservations?caseId=${row.caseId}`}><b className="c02-mono">{caseNumber(row.caseId, row.createdAt)}</b><span>상속예금 해지 및 지급 사전접수 · 진행 가능</span></Link></li>)}</ul>}
    </section>
  </main>

  if (!snapshot) return <main className="c02-detail">{error ? <p className="c02-alert" role="alert">{error}</p> : <p className="c02-notice" role="status">예약 정보를 불러오고 있어요.</p>}</main>
  const number = caseNumber(snapshot.caseId, snapshot.timeline?.createdAt)
  const eligible = snapshot.caseStatus === 'REVIEW_PASSED' && Boolean(snapshot.finalDecision)
  const header = <>
    <nav className="c02-breadcrumb" aria-label="현재 위치"><Link to="/tasks/c02">상속예금 접수 내역</Link><span>›</span><Link to={detailHref}>상속예금 사전접수</Link><span>›</span><b>방문 영업점 예약</b></nav>
    <h1 className="c02-reserve__title">방문 영업점 예약</h1>
    <div className="c02-pills"><span className="c02-chip">{number} · 상속예금 해지 및 지급 사전접수</span>{eligible && <span className="c02-pill c02-pill--success">직원 검토 결과 진행 가능 · {formatDateTime(snapshot.timeline?.resultNotifiedAt)}</span>}</div>
    {error && <p className="c02-alert" role="alert">{error}</p>}
  </>

  if (!eligible) return <main className="c02-detail">{header}<section className="c02-detail__card"><p className="c02-notice">직원 검토 결과가 ‘진행 가능’으로 안내된 뒤에 예약할 수 있어요.</p><Link className="c02-primary c02-link-button" to={detailHref}>접수 상세로</Link></section></main>

  if (existing) {
    const locked = new Date(existing.startsAt).getTime() - Date.now() <= LOCK_MS
    return <main className="c02-detail">{header}
      <section className="c02-detail__card c02-reservation is-open">
        <div className="c02-reservation__title"><h2>예약된 방문 일정</h2><span className="c02-pill c02-pill--muted">{existing.status === 'CONFIRMED' ? '예약 확정' : existing.status === 'NEEDS_MANUAL_ASSIGNMENT' ? '수동 배정 대기' : '배정 중'}</span></div>
        <dl className="c02-modal__summary"><div><dt>영업점</dt><dd>{branches.find(item => item.id === existing.branchId)?.name ?? `지점 #${existing.branchId}`}</dd></div><div><dt>방문 일시</dt><dd>{formatDateTime(existing.startsAt)}</dd></div></dl>
        <p className="c02-helper c02-helper--muted">{locked ? '방문 24시간 전이라 변경·취소할 수 없어요. 영업점에 문의해 주세요.' : '방문 24시간 전까지 취소한 뒤 다시 예약할 수 있어요.'}</p>
        <div className="c02-detail__actions"><Link className="c02-secondary c02-link-button" to={detailHref}>접수 상세로</Link><button type="button" className="c02-danger" disabled={busy || locked} onClick={cancel}>예약 취소</button></div>
      </section>
    </main>
  }

  return <main className="c02-detail">{header}
    <div className="c02-reserve">
      <div className="c02-detail__stack">
        <section className="c02-detail__card"><h2>1. 영업점</h2>
          <div className="c02-branches">{branches.length === 0 ? <p className="c02-notice">예약 가능한 영업점이 없어요.</p> : branches.map(item => <button type="button" key={item.id} className={`c02-branch${branchId === item.id ? ' is-selected' : ''}`} aria-pressed={branchId === item.id} onClick={() => setBranchId(item.id)}><span><b>{item.name}</b><small>{item.address} · 평일 10:00–16:00</small></span><em>상속예금 상담 가능</em></button>)}</div>
        </section>
        <section className="c02-detail__card"><h2>2. 방문 날짜</h2>
          <div className="c02-days">{days.map((item, index) => { const closed = isWeekend(item); const key = dayKey(item); return <button type="button" key={key} disabled={closed} className={day === key ? 'is-selected' : ''} aria-pressed={day === key} onClick={() => { setDay(key); setHour(null) }}><small>{WEEKDAY[item.getDay()]}</small><b>{item.getDate()}</b>{closed ? <small>휴무</small> : index === 0 ? <small>내일</small> : null}</button> })}</div>
        </section>
        <section className="c02-detail__card"><div className="c02-card__title"><h2>3. 방문 시간</h2><span>{selectedDay ? `${dayLabel(selectedDay)} · 1시간 단위` : '날짜를 먼저 골라 주세요'}</span></div>
          <div className="c02-hours">{HOURS.map(value => { const open = selectedDay ? bookable(slotTime(selectedDay, value)) : false; return <button type="button" key={value} disabled={!open} className={hour === value ? 'is-selected' : ''} aria-pressed={hour === value} onClick={() => setHour(value)}><b>{pad2(value)}:00</b>{selectedDay && !open && <small>마감</small>}</button> })}</div>
          <p className="c02-helper c02-helper--muted">방문 24시간 이내 시간은 고를 수 없어요.</p>
        </section>
      </div>
      <aside className="c02-detail__card c02-reserve__summary"><h2>선택한 일정</h2>
        <dl className="c02-modal__summary"><div><dt>영업점</dt><dd><b>{branch?.name ?? '—'}</b></dd></div><div><dt>날짜</dt><dd><b>{selectedDay ? dayLabel(selectedDay) : '—'}</b></dd></div><div><dt>시간</dt><dd><b>{hour !== null ? `${pad2(hour)}:00` : '—'}</b></dd></div></dl>
        <span className="c02-pill c02-pill--muted">선택됨 · 아직 예약 확정 아님</span>
        <p className="c02-helper">고르기만 해서는 자리가 잡히지 않아요. 예약을 요청하면 서버가 자격을 다시 확인하고 그 지점의 담당 직원을 배정해요.</p>
        <button type="button" className="c02-primary" disabled={!branch || !selectedAt || busy} onClick={reserve}>{busy ? '요청 중' : '예약 요청'}</button>
        <p className="c02-helper c02-helper--muted">예약은 방문 24시간 전까지 변경·취소할 수 있어요. 예약은 예금 지급 가능 판정이 아니에요.</p>
      </aside>
    </div>
  </main>
}
