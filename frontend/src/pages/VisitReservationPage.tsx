import { useEffect, useState } from 'react'
import { c02Api, taskBffEnabled, type Reservation, type ReservationBranch, type ReservationEligibleCase } from '../api/taskApi'
import { reservationStatusLabel } from './reservationStatus'

const requestId = (action: string, id: number | string) => `visit-${action}-${id}-${crypto.randomUUID()}`
const formatSeoul = (value: string) => new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short',
}).format(new Date(value))
const changeAllowed = (reservation: Reservation) =>
  Date.now() <= Date.parse(reservation.startsAt) - 24 * 60 * 60 * 1000
const asSeoulInstant = (localValue: string) => new Date(`${localValue}:00+09:00`).toISOString()

export function VisitReservationPage() {
  const [reservations, setReservations] = useState<Reservation[]>([])
  const [cases, setCases] = useState<ReservationEligibleCase[]>([])
  const [branches, setBranches] = useState<ReservationBranch[]>([])
  const [caseId, setCaseId] = useState('')
  const [branchId, setBranchId] = useState('')
  const [startsAt, setStartsAt] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = async () => {
    if (!taskBffEnabled) return
    setBusy(true); setError(null)
    try {
      const [reservationResponse, caseResponse, branchResponse] = await Promise.all([
        c02Api.reservations(requestId('list', 'customer')),
        c02Api.reservationEligibleCases(requestId('eligible-cases', 'customer')),
        c02Api.reservationBranches(requestId('branches', 'customer')),
      ])
      const rows = reservationResponse.data ?? []
      const availableCases = caseResponse.data ?? []
      const availableBranches = branchResponse.data ?? []
      setReservations(rows); setCases(availableCases); setBranches(availableBranches)
      setCaseId(current => current || (availableCases[0] ? String(availableCases[0].caseId) : ''))
      setBranchId(current => current || (availableBranches[0] ? String(availableBranches[0].branchId) : ''))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load reservation data.')
    } finally { setBusy(false) }
  }

  useEffect(() => { void load() }, [])

  const create = async () => {
    if (!caseId || !branchId || !startsAt) { setError('Choose an eligible Case, branch, and visit time.'); return }
    setBusy(true); setError(null); setNotice(null)
    try {
      const response = await c02Api.createReservation({
        requestId: requestId('create', caseId), caseId: Number(caseId), branchId: Number(branchId),
        startsAt: asSeoulInstant(startsAt),
      })
      setNotice(response.data?.status === 'CONFIRMED'
        ? '방문 예약이 확정되었습니다.'
        : '예약 요청이 접수됐습니다. 직원 배정과 예약 확정은 아직 완료되지 않았습니다.')
      setStartsAt(''); await load()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not create the visit request.')
    } finally { setBusy(false) }
  }

  const cancel = async (reservation: Reservation) => {
    setBusy(true); setError(null); setNotice(null)
    try {
      await c02Api.cancelReservation(reservation.id, requestId('cancel', reservation.id))
      setNotice('Cancellation request completed.'); await load()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not cancel this reservation.')
    } finally { setBusy(false) }
  }

  const reschedule = async (reservation: Reservation) => {
    if (!branchId || !startsAt) { setError('Choose a new branch and visit time first.'); return }
    setBusy(true); setError(null); setNotice(null)
    try {
      const response = await c02Api.rescheduleReservation(reservation.id, {
        requestId: requestId('change', reservation.id), branchId: Number(branchId),
        startsAt: asSeoulInstant(startsAt),
      })
      setNotice(response.data?.status === 'CONFIRMED'
        ? '방문 예약 변경이 확정되었습니다.'
        : '변경 요청이 접수됐습니다. 직원 배정과 예약 확정은 아직 완료되지 않았습니다.')
      setStartsAt(''); await load()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not change this reservation.')
    } finally { setBusy(false) }
  }

  return <section>
    <h1>Branch visit reservations</h1>
    <p role="status">A visit request is available only for your notified Case with a staff PROCEED decision. Staff assignment is separate from the selected task code and is not automated in this flow.</p>
    {!taskBffEnabled && <p role="status">Customer BFF is not enabled in this environment.</p>}
    {error && <p role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    <fieldset disabled={!taskBffEnabled || busy}>
      <legend>Request a visit</legend>
      <label>Eligible Case / task
        <select value={caseId} onChange={event => setCaseId(event.target.value)}>
          <option value="">Select a Case</option>
          {cases.map(item => <option key={item.caseId} value={item.caseId}>Case {item.caseId} · {item.taskCode} · revision {item.revision}</option>)}
        </select>
      </label>
      <label>Branch
        <select value={branchId} onChange={event => setBranchId(event.target.value)}>
          <option value="">Select a branch</option>
          {branches.map(branch => <option key={branch.branchId} value={branch.branchId}>{branch.name} ({branch.branchCode})</option>)}
        </select>
      </label>
      <label>Visit date and time (Korea Standard Time)
        <input type="datetime-local" value={startsAt} onChange={event => setStartsAt(event.target.value)} />
      </label>
      <button type="button" disabled={!caseId || !branchId || !startsAt} onClick={() => void create()}>Request visit</button>
      <p>Until branch slot and capacity data are available, the server accepts at most one active request for a branch and time. A saved request remains pending staff assignment.</p>
    </fieldset>
    <button type="button" disabled={!taskBffEnabled || busy} onClick={() => void load()}>Refresh</button>
    {reservations.length === 0 ? <p>No reservations were returned.</p> : <ul>
      {reservations.map(reservation => {
        const canChange = changeAllowed(reservation)
        const active = reservation.status !== 'CANCELED'
        return <li key={reservation.id}>
          <p>{reservation.taskCode} · WorkItem {reservation.workItemId} · Case {reservation.caseId} · branch {reservation.branchId} · staff {reservation.staffId ?? 'not assigned'}</p>
          <p>{formatSeoul(reservation.startsAt)} (Asia/Seoul) · {reservationStatusLabel(reservation.status)}</p>
          {reservation.status === 'PENDING_ASSIGNMENT' && <p>예약 요청 접수 상태입니다. 직원 배정 전이며 방문 예약은 아직 확정되지 않았습니다.</p>}
          {reservation.status === 'NEEDS_MANUAL_ASSIGNMENT' && <p>직원의 수동 배정이 필요합니다. 방문 예약은 아직 확정되지 않았습니다.</p>}
          {active && !canChange && <p>Changes and cancellation are closed within 24 hours of the visit. The server enforces this limit.</p>}
          {active && canChange && <>
            <button type="button" disabled={!taskBffEnabled || busy} onClick={() => void cancel(reservation)}>Cancel request</button>
            <button type="button" disabled={!taskBffEnabled || busy || !branchId || !startsAt} onClick={() => void reschedule(reservation)}>Change to selected branch and time</button>
          </>}
        </li>
      })}
    </ul>}
  </section>
}
