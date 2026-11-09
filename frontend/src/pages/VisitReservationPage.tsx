import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { toUserMessageOr } from '../api/errorMessages'
import { c02Api, taskBffEnabled, type Branch, type C02CaseRow, type C02CustomerCaseSnapshot, type Reservation, type ReservationSlot } from '../api/taskApi'
import { caseNumber, formatDateTime, reservationNumber } from '../features/c02/c02CaseView'
import '../features/t2/customer-pages.css'
import '../features/c02/c02-case.css'

// Visiting hours and free times come from the server; branch_slot is not in the Flyway schema, so no seat counts are shown.
const LOCK_MS = 24 * 60 * 60 * 1000
const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토']
const pad2 = (value: number) => String(value).padStart(2, '0')
const dayKey = (date: Date) => `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
const dayLabel = (date: Date) => `${pad2(date.getMonth() + 1)}.${pad2(date.getDate())}(${WEEKDAY[date.getDay()]})`

function nextDays(count: number): Date[] {
  const today = new Date(); today.setHours(0, 0, 0, 0)
  return Array.from({ length: count }, (_, index) => { const day = new Date(today); day.setDate(today.getDate() + index + 1); return day })
}
const isWeekend = (day: Date) => day.getDay() === 0 || day.getDay() === 6
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
  const [startsAt, setStartsAt] = useState<string | null>(null)
  const [slots, setSlots] = useState<ReservationSlot[] | null>(null)
  const [branchQuery, setBranchQuery] = useState('')
  const [availabilityVersion, setAvailabilityVersion] = useState(0)
  const [busy, setBusy] = useState(false)
  // VISIT-10: change keeps the current booking until the server has secured the new time.
  const [changing, setChanging] = useState(params.get('change') === '1')
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
        setExisting(reservations.data?.find(item => item.caseId === caseId && item.status !== 'CANCELED' && item.status !== 'EXPIRED') ?? null)
        if (branchRows.data?.length === 1) setBranchId(branchRows.data[0].id)
      } catch (cause) { if (active) setError(toUserMessageOr(cause, '예약 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.')) }
    }
    void load()
    return () => { active = false }
  }, [caseId, hasCase])

  const selectedDay = days.find(item => dayKey(item) === day) ?? null
  useEffect(() => {
    setStartsAt(null); setSlots(null)
    if (!taskBffEnabled || branchId === null || day === null) return
    let active = true
    c02Api.reservationAvailability(branchId, day, `reservation-slots-${Date.now()}`)
      .then(response => { if (active) setSlots(response.data ?? []) })
      .catch(cause => { if (active) { setSlots([]); setError(toUserMessageOr(cause, '예약 가능 시간을 불러오지 못했어요.')) } })
    return () => { active = false }
  }, [branchId, day, availabilityVersion])
  const branch = branches.find(item => item.id === branchId) ?? null
  const visibleBranches = branches.filter(item => `${item.name} ${item.address}`.includes(branchQuery.trim()))
  const detailHref = `/tasks/c02/cases/${caseId}`

  const reserve = async () => {
    if (!branch || !startsAt || !snapshot) return
    setBusy(true); setError(null)
    try {
      const body = { requestId: `reservation-${changing && existing ? 'change' : 'create'}-${caseId}-${crypto.randomUUID()}`, caseId, branchId: branch.id, staffId: null, startsAt }
      if (changing && existing) await c02Api.changeReservation(existing.id, body)
      else await c02Api.createReservation(body)
      navigate(detailHref)
    } catch (cause) {
      setError(toUserMessageOr(cause, '예약을 요청하지 못했어요. 잠시 후 다시 시도해 주세요.'))
      setStartsAt(null); setAvailabilityVersion(version => version + 1)
    } finally { setBusy(false) }
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
    <div className="v17-vb__head">
      <nav className="v17-vb__crumb" aria-label="현재 위치"><Link to="/work">업무 요청 기록</Link><span>›</span><Link to={detailHref}>상속예금 사전접수</Link><span>›</span><span className="is-current">방문 영업점 예약</span></nav>
      <h1>방문 영업점 예약</h1>
      <div className="v17-vb__chips"><span>{number} · 상속예금 해지 및 지급 사전접수</span>{eligible && <span className="is-ok">직원 검토 결과 진행 가능 · {formatDateTime(snapshot.timeline?.resultNotifiedAt)}</span>}</div>
    </div>
    {error && <p className="c02-alert" role="alert">{error}</p>}
  </>

  if (!eligible) return <main className="c02-detail">{header}<section className="c02-detail__card"><p className="c02-notice">직원 검토 결과가 ‘진행 가능’으로 안내된 뒤에 예약할 수 있어요.</p><Link className="c02-primary c02-link-button" to={detailHref}>접수 상세로</Link></section></main>

  if (existing && !(changing && new Date(existing.startsAt).getTime() - Date.now() > LOCK_MS)) {
    const locked = new Date(existing.startsAt).getTime() - Date.now() <= LOCK_MS
    const reservationState = locked ? 'locked' : existing.status === 'CONFIRMED' ? 'confirmed' : existing.status === 'NEEDS_MANUAL_ASSIGNMENT' ? 'manual' : 'pending'
    const reservationLabel = reservationState === 'confirmed' || reservationState === 'locked' ? '예약 확정' : reservationState === 'manual' ? '수동 배정 대기' : '직원 배정 중'
    const reservationMessage = reservationState === 'confirmed'
      ? '예약이 확정되었어요. 방문 전 접수 내용과 준비서류를 다시 확인해 주세요.'
      : reservationState === 'manual'
        ? '자동 배정이 완료되지 않아 영업점에서 담당 직원을 직접 배정하고 있어요. 예약 확정 전 상태입니다.'
        : '선택한 시간의 정원을 확인하고 담당 직원을 배정하고 있어요. 예약 확정 전 상태입니다.'
    return <main className="c02-detail">{header}
      <section aria-label={reservationLabel} aria-description={reservationMessage} className={`c02-detail__card c02-reservation c02-reservation--${reservationState} is-open`}>
        <div className="c02-reservation__title"><h2>예약된 방문 일정</h2><span className="c02-pill c02-pill--muted">{existing.status === 'CONFIRMED' ? '예약 확정' : existing.status === 'NEEDS_MANUAL_ASSIGNMENT' ? '수동 배정 대기' : '배정 중'}</span></div>
        <dl className="c02-modal__summary"><div><dt>예약번호</dt><dd>{reservationNumber(existing.id)}</dd></div><div><dt>영업점</dt><dd>{branches.find(item => item.id === existing.branchId)?.name ?? `지점 #${existing.branchId}`}</dd></div><div><dt>방문 일시</dt><dd>{formatDateTime(existing.startsAt)}</dd></div></dl>
        <p className="c02-helper c02-helper--muted">{locked ? '방문 24시간 전이라 변경·취소할 수 없어요. 영업점에 문의해 주세요.' : '방문 24시간 전까지 일정을 바꾸거나 취소할 수 있어요.'}</p>
        <div className="c02-detail__actions"><Link className="c02-secondary c02-link-button" to={detailHref}>접수 상세로</Link><button type="button" className="c02-secondary" disabled={busy || locked} onClick={() => setChanging(true)}>일정 변경</button><button type="button" className="c02-danger" disabled={busy || locked} onClick={cancel}>예약 취소</button></div>
      </section>
    </main>
  }

  return <main className="v17-vb">{header}
    <div className="v17-vb__cols">
      <div className="v17-vb__main">
        <section className="v17-vb__card"><h2>1. 영업점</h2>
          {visibleBranches.length === 0 ? <p className="c02-notice">예약 가능한 영업점이 없어요.</p> : visibleBranches.map(item => { const on = branchId === item.id; return <button type="button" key={item.id} className={`v17-vb__branch${on ? ' is-on' : ''}`} aria-pressed={on} onClick={() => setBranchId(item.id)}>
            <BuildingIcon /><span><strong>{item.name}</strong><span>{item.address} · 평일 10:00–16:00 (모의)</span></span><span className="v17-vb__branch-tag">상속예금 상담 가능</span>
          </button> })}
        </section>
        <section className="v17-vb__card"><h2>2. 방문 날짜</h2>
          <div className="v17-vb__days">{days.map((item, index) => { const closed = isWeekend(item); const key = dayKey(item); const on = day === key; return <button type="button" key={key} disabled={closed} className={on ? 'is-on' : undefined} aria-pressed={on} onClick={() => setDay(key)}><span>{WEEKDAY[item.getDay()]}</span><strong>{item.getDate()}</strong><span>{closed ? '휴무' : index === 0 ? '내일' : ''}</span></button> })}</div>
        </section>
        <section className="v17-vb__card"><div className="v17-vb__title"><h2>3. 방문 시간</h2><span>{selectedDay ? `${dayLabel(selectedDay)} · 1시간 단위` : '날짜를 먼저 골라 주세요'}</span></div>
          <div className="v17-vb__hours">{!selectedDay ? null : slots === null ? <p className="c02-notice" role="status">예약 가능 시간을 확인하고 있어요.</p> : slots.length === 0 ? <p className="c02-notice">이 날짜에는 예약할 수 있는 시간이 없어요.</p> : slots.map(slot => { const value = new Date(slot.startsAt); const label = `${pad2(value.getHours())}:00`; const on = startsAt === slot.startsAt; return <button type="button" key={slot.startsAt} disabled={!slot.available} className={on ? 'is-on' : undefined} aria-pressed={on} onClick={() => setStartsAt(slot.startsAt)}><strong>{label}</strong>{!slot.available ? <span>마감</span> : slot.remaining !== undefined && <span>잔여 {slot.remaining}</span>}</button> })}</div>
          <p className="v17-vb__fine">잔여는 지금 이 시간에 비어 있는 그 지점 담당 직원 수예요. 다른 고객이 먼저 예약하면 바뀔 수 있고, 예약 요청 때 서버가 다시 확인해요. 운영시간·슬롯 단위·예약 가능 기간은 시연용 모의값이에요.</p>
        </section>
      </div>
      <aside className="v17-vb__side"><h2>선택한 일정</h2>
        <dl><dt>영업점</dt><dd>{branch?.name ?? '—'}</dd><dt>날짜</dt><dd>{selectedDay ? dayLabel(selectedDay) : '—'}</dd><dt>시간</dt><dd>{startsAt ? `${pad2(new Date(startsAt).getHours())}:00` : '—'}</dd></dl>
        {changing && existing && <p className="v17-vb__fine">현재 예약: {formatDateTime(existing.startsAt)} · {branches.find(item => item.id === existing.branchId)?.name ?? `지점 #${existing.branchId}`}. 새 시간이 확보되면 기존 시간과 담당 직원 배정이 풀리고, 확보되지 않으면 기존 예약이 그대로 유지돼요.</p>}
        <span className="v17-vb__state">선택됨 · 아직 예약 확정 아님</span>
        <p>고르기만 해서는 자리가 잡히지 않아요. 예약을 요청하면 서버가 자격과 정원을 다시 확인하고 그 지점의 담당 직원을 배정해요.</p>
        <button type="button" className="v17-vb__request" disabled={!branch || !startsAt || busy} onClick={reserve}>{busy ? '요청 중' : changing && existing ? '이 시간으로 변경' : '예약 요청'}</button>
        {changing && existing && <button type="button" className="c02-secondary" disabled={busy} onClick={() => setChanging(false)}>변경 그만두기</button>}
        <span className="v17-vb__fine">예약은 방문 24시간 전까지 변경·취소할 수 있어요. 예약은 예금 지급 가능 판정이 아니에요.</span>
      </aside>
    </div>
    <label className="v17-vb__search">영업점 검색<input value={branchQuery} onChange={event => setBranchQuery(event.target.value)} placeholder="지점명 또는 주소" /></label>
  </main>
}

function BuildingIcon() {
  return <svg className="v17-vb__icon" width="22" height="22" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 21h16M5 21V10l7-5 7 5v11M10 21v-5h4v5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
}
