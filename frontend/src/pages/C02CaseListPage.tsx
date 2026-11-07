import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { toUserMessageOr } from '../api/errorMessages'
import { c02Api, taskBffEnabled, type C02CaseRow } from '../api/taskApi'
import { caseNumber, casePill, formatDateTime } from '../features/c02/c02CaseView'
import '../features/t2/customer-pages.css'
import '../features/c02/c02-case.css'

/** The customer's own C-02 Cases. Drafts reopen the intake page; everything else opens the Case detail. */
export function C02CaseListPage() {
  const navigate = useNavigate()
  const [rows, setRows] = useState<C02CaseRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)
  useEffect(() => {
    if (!taskBffEnabled) return
    let active = true
    c02Api.customerCases(`c02-cases-${Date.now()}`)
      .then(response => { if (active) setRows(response.data ?? []) })
      .catch(cause => { if (active) setError(toUserMessageOr(cause, '접수 내역을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.')) })
    return () => { active = false }
  }, [])
  const start = async () => {
    if (starting) return
    setStarting(true)
    setError(null)
    try {
      const response = await c02Api.startWorkItem(`c02-start-${Date.now()}`)
      // ApiEnvelope.workItemId is the routing contract.  The nested StartResult
      // is only a confirmation payload; reject a contradictory response rather
      // than routing the form with an ambiguous id.
      const workItemId = response.workItemId
      if (!Number.isSafeInteger(workItemId) || !workItemId ||
          (response.data?.workItemId != null && response.data.workItemId !== workItemId)) {
        throw new Error('No valid C-02 work item was returned.')
      }
      navigate(`/tasks/c02/${workItemId}`)
    } catch (cause) {
      setError(toUserMessageOr(cause, '새 상속예금 사전접수를 시작하지 못했어요. 잠시 후 다시 시도해 주세요.'))
    } finally {
      setStarting(false)
    }
  }
  return <main className="c02-detail">
    <nav className="c02-breadcrumb" aria-label="현재 위치"><Link to="/">홈</Link><span>›</span><b>상속예금 접수 내역</b></nav>
    <h1 className="c02-reserve__title">상속예금 접수 내역</h1>
    {error && <p className="c02-alert" role="alert">{error}</p>}
    <section className="c02-detail__card">
      <button type="button" className="c02-link-button" disabled={!taskBffEnabled || starting} onClick={start}>
        {starting ? '새 신청을 준비하고 있어요…' : '새 상속예금 사전접수 시작'}
      </button>
      {rows === null && !error && <p className="c02-notice" role="status">접수 내역을 불러오고 있어요.</p>}
      {rows?.length === 0 && <p className="c02-notice">아직 상속예금 접수가 없어요. 위 버튼으로 새 사전접수를 시작하거나, 안내가 필요하면 <Link to="/chat">채팅으로 가기</Link>를 이용해 주세요.</p>}
      {rows && rows.length > 0 && <ul className="c02-case-list">{rows.map(row => {
        const pill = casePill(row.caseStatus, row.reviewStatus)
        const href = row.caseStatus === 'DRAFT' ? `/tasks/c02/${row.workItemId}` : `/tasks/c02/cases/${row.caseId}`
        return <li key={row.caseId}><Link to={href}>
          <b className="c02-mono">{caseNumber(row.caseId, row.createdAt)}</b>
          <span>상속예금 해지 및 지급 사전접수 · {row.submittedAt ? `접수 ${formatDateTime(row.submittedAt)}` : `작성 시작 ${formatDateTime(row.createdAt)}`}</span>
          <span className={`c02-pill c02-pill--${pill.tone}`}>{pill.label}</span>
          {row.caseStatus === 'REVIEW_PASSED' && <em>방문 예약 가능</em>}
        </Link></li>
      })}</ul>}
    </section>
  </main>
}
