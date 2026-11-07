import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { toUserMessageOr } from '../../api/errorMessages'
import { c02Api, taskBffEnabled, type BranchReservations, type C02CaseRow } from '../../api/taskApi'
import { caseNumber, formatDateTime, reservationNumber } from '../c02/c02CaseView'

const dateLabel = new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })

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
  return <section className="staff-card" aria-labelledby="case-open-title">
    <div className="staff-card__head"><h2 id="case-open-title">C-02 Case 바로 열기</h2><span>상속예금 사전접수 · Case 번호로 직원 검토 화면을 열어요</span></div>
    <form className="staff-case-form" onSubmit={submit} noValidate>
      <label htmlFor="staff-case-id">Case 번호</label>
      <div className="staff-case-form__row">
        <input id="staff-case-id" inputMode="numeric" value={value} aria-invalid={invalid} aria-describedby={invalid ? 'staff-case-error' : undefined}
          onChange={event => { setValue(event.target.value); setInvalid(false) }} placeholder="예) 2" />
        <button type="submit">검토 화면 열기</button>
      </div>
      {invalid && <span id="staff-case-error" role="alert" className="staff-case-form__error">1 이상의 Case 번호를 입력해 주세요.</span>}
    </form>
  </section>
}

type Tone = { label: string; tone: string }
const CASE_STATUS: Record<string, Tone> = {
  SUBMITTED: { label: '검토 대기', tone: 'info' }, IN_REVIEW: { label: '검토 중', tone: 'info' },
  REVIEW_PASSED: { label: '결과 등록 · 진행 가능', tone: 'success' }, REVIEW_REJECTED_REQUIREMENT: { label: '결과 등록 · 요건 미충족', tone: 'danger' },
  REVIEW_REJECTED_DOCUMENT: { label: '결과 등록 · 서류 부족', tone: 'danger' }, CANCELED: { label: '고객 취소', tone: 'muted' },
}
const REVIEW_STATUS: Record<string, Tone> = {
  PENDING: { label: '점검 대기', tone: 'muted' }, RUNNING: { label: '점검 중', tone: 'info' }, READY_FOR_STAFF: { label: '직원 검토 가능', tone: 'success' },
  WAITING_CUSTOMER: { label: '고객 확인 대기', tone: 'warn' }, FAILED_NEEDS_STAFF: { label: '직원 확인 필요', tone: 'danger' },
}
const RESERVATION_STATUS: Record<string, Tone> = {
  CONFIRMED: { label: '예약 확정', tone: 'success' }, PENDING_ASSIGNMENT: { label: '배정 중', tone: 'info' },
  SELECTED: { label: '선택됨', tone: 'muted' }, NEEDS_MANUAL_ASSIGNMENT: { label: '수동 배정 필요', tone: 'warn' },
}
const needsAction = (row: C02CaseRow) => row.caseStatus === 'SUBMITTED' || row.caseStatus === 'IN_REVIEW'
const decided = (row: C02CaseRow) => row.caseStatus.startsWith('REVIEW_')

function Badge({ value, map }: { value: string | null; map: Record<string, Tone> }) {
  const item = value ? map[value] : undefined
  return <span className={`staff-badge staff-badge--${item?.tone ?? 'muted'}`}>{item?.label ?? '—'}</span>
}

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

function CaseTable({ rows }: { rows: C02CaseRow[] }) {
  if (rows.length === 0) return <p className="staff-empty">해당하는 Case가 없어요.</p>
  return <div className="staff-table-wrap"><table className="staff-table">
    <thead><tr><th>접수번호</th><th>업무 · 고객</th><th>접수일시</th><th>처리 상태</th><th>AI 사전점검</th><th><span className="staff-sr">열기</span></th></tr></thead>
    <tbody>{rows.map(row => <tr key={row.caseId} className={row.reviewStatus === 'FAILED_NEEDS_STAFF' && needsAction(row) ? 'is-flagged' : ''}>
      <td className="staff-mono">{caseNumber(row.caseId, row.createdAt)}</td>
      <td><span className="staff-task">C-02</span> <b>{row.customerName}</b><small>상속예금 해지 및 지급 사전접수</small></td>
      <td>{formatDateTime(row.submittedAt)}</td>
      <td><Badge value={row.caseStatus} map={CASE_STATUS} />{row.decidedAt && <small>{formatDateTime(row.decidedAt)} 등록</small>}</td>
      <td>{needsAction(row) ? <><Badge value={row.reviewStatus} map={REVIEW_STATUS} /><small className="staff-mono">{row.reviewStatus ?? ''}</small></> : '—'}</td>
      <td><Link className="staff-button staff-button--small" to={`/staff/c02/${row.caseId}`}>{needsAction(row) ? '검토하기' : '보기'}</Link></td>
    </tr>)}</tbody>
  </table></div>
}

const TABS = [
  { key: 'action', label: '처리 필요', filter: needsAction },
  { key: 'done', label: '결과 등록', filter: decided },
  { key: 'all', label: '전체', filter: (_row: C02CaseRow) => true },
] as const
type TabKey = (typeof TABS)[number]['key']

/** ui-reference 62 (dashboard): counts come from the same read-only list as the queue. */
export function StaffDashboardPage() {
  const { user } = useAuth()
  const { rows, error } = useStaffCases()
  const open = rows?.filter(needsAction) ?? []
  return <div className="staff-page">
    <div className="staff-page__head">
      <div>
        <h1>{user?.name ?? '직원'}님, 오늘도 힘찬 하루 보내세요!</h1>
        <span>{dateLabel.format(new Date())} · {user?.role === 'ADMIN' ? '관리자' : '직원'}</span>
      </div>
      <div className="staff-page__actions">
        <Link to="/staff/queue" className="staff-button">사전검토 업무함</Link>
        <Link to="/staff/branch" className="staff-button staff-button--secondary">지점 예약 업무함</Link>
      </div>
    </div>
    {error && <p className="staff-alert" role="alert">{error}</p>}
    <div className="staff-stats">
      <div className="staff-stat"><span>처리 필요</span><b>{rows ? open.length : '—'}</b></div>
      <div className="staff-stat"><span>직원 확인 필요 (AI 점검 실패)</span><b>{rows ? open.filter(row => row.reviewStatus === 'FAILED_NEEDS_STAFF').length : '—'}</b></div>
      <div className="staff-stat"><span>결과 등록 완료</span><b>{rows ? rows.filter(decided).length : '—'}</b></div>
    </div>
    <section className="staff-card"><div className="staff-card__head"><h2>먼저 처리할 Case</h2><span>제출 순서 · AI 사전점검 상태는 직원 검토를 대신하지 않아요</span></div>
      {rows ? <CaseTable rows={open.slice(0, 5)} /> : !error && <p className="staff-empty">불러오는 중이에요.</p>}
    </section>
    <CaseOpenCard />
  </div>
}

export function StaffQueuePage() {
  const { rows, error } = useStaffCases()
  const [tab, setTab] = useState<TabKey>('action')
  const current = TABS.find(item => item.key === tab) ?? TABS[0]
  return <div className="staff-page">
    <div className="staff-page__head"><div><h1>사전검토 업무함</h1><span>고객이 제출한 C업무 · 임시저장 중인 업무는 나타나지 않아요. 처리 상태와 AI 사전점검 상태는 서로 다른 축이에요.</span></div></div>
    {error && <p className="staff-alert" role="alert">{error}</p>}
    <div className="staff-tabs" role="tablist">{TABS.map(item => <button type="button" role="tab" key={item.key} aria-selected={tab === item.key} className={tab === item.key ? 'is-active' : ''} onClick={() => setTab(item.key)}>{item.label} {rows ? rows.filter(item.filter).length : ''}</button>)}</div>
    <section className="staff-card">{rows ? <CaseTable rows={rows.filter(current.filter)} /> : !error && <p className="staff-empty">불러오는 중이에요.</p>}
      <p className="staff-note">AI가 ‘직원 검토 가능’으로 표시해도 직원 검토를 생략하지 않아요. 업무함 열람 범위는 팀 결정 전이라, 시연에서는 사전검토 권한이 있는 직원에게만 보여요.</p>
    </section>
    <CaseOpenCard />
  </div>
}

export function StaffBranchPage() {
  const { user } = useAuth()
  const [data, setData] = useState<BranchReservations | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (!taskBffEnabled) return
    let active = true
    c02Api.staffReservations(`staff-reservations-${Date.now()}`)
      .then(response => { if (active) setData(response.data) })
      .catch(cause => { if (active) setError(toUserMessageOr(cause, '예약 업무함을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.')) })
    return () => { active = false }
  }, [])
  return <div className="staff-page">
    <div className="staff-page__head"><div><h1>{data?.branch.name ?? '지점'} 예약 업무함</h1><span>예약 후 Case는 소속 지점 업무함에서만 열람해요 · 담당 직원은 Spring이 지점 직원 후보 안에서 확정해요</span></div></div>
    {error && <p className="staff-alert" role="alert">{error}</p>}
    <section className="staff-card">
      {!data && !error && <p className="staff-empty">불러오는 중이에요.</p>}
      {data && data.reservations.length === 0 && <p className="staff-empty">예정된 방문 예약이 없어요.</p>}
      {data && data.reservations.length > 0 && <div className="staff-table-wrap"><table className="staff-table">
        <thead><tr><th>예약번호</th><th>방문 일시</th><th>Case</th><th>고객</th><th>예약 상태</th><th>담당 직원</th><th><span className="staff-sr">열기</span></th></tr></thead>
        <tbody>{data.reservations.map(item => <tr key={item.id}>
          <td className="staff-mono">{reservationNumber(item.id)}</td>
          <td><b>{formatDateTime(item.startsAt)}</b></td>
          <td className="staff-mono">#{item.caseId}</td>
          <td><span className="staff-task">C-02</span> <b>{item.customerName}</b></td>
          <td><Badge value={item.status} map={RESERVATION_STATUS} /></td>
          <td>{item.assignedStaffName ? `${item.assignedStaffName}${item.assignedStaffId === user?.userId ? ' (나)' : ''}` : '미배정'}</td>
          <td><Link className="staff-button staff-button--small staff-button--secondary" to={`/staff/c02/${item.caseId}`}>Case 보기</Link></td>
        </tr>)}</tbody>
      </table></div>}
      <p className="staff-note">AI 담당자 추천은 Spring이 고른 지점 직원 후보 안에서 업무코드·업무량으로만 순위를 제안해요. 추천이 실패해도 직원 검토 결과는 바뀌지 않아요.</p>
    </section>
  </div>
}
