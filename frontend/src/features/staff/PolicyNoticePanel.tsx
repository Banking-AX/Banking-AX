import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { REASON_LABELS, policyChangeApi, type PolicyNotice, type PolicyNotices } from '../../api/policyChangeApi'
import { formatDateTime } from '../c02/c02CaseView'
import './policy-changes.css'

function reasons(row: PolicyNotice) {
  return row.reasonCodes.length > 0 ? ` · 이 Case 관련: ${row.reasonCodes.map(code => REASON_LABELS[code] ?? code).join(', ')}` : ''
}

/** Reusable on any staff screen. With a caseId it lists only changes concerning that Case; none of this is a rule. */
export function PolicyNoticePanel({ taskCode, caseId }: { taskCode: string; caseId?: number }) {
  const [notices, setNotices] = useState<PolicyNotices | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let active = true
    policyChangeApi.notices(taskCode, caseId)
      .then(response => { if (active) setNotices(response.data ?? null) })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [taskCode, caseId])

  if (failed) return <p className="policy-note">업무기준 변경 안내를 확인하지 못했어요.</p>
  if (!notices || notices.approved.length + notices.pending.length === 0) return null
  return <section className="policy-panel" aria-label="업무기준 변경 안내">
    {notices.approved.map(row => <p key={row.id}>
      <span className="staff-badge staff-badge--success">{row.effectiveState === 'SCHEDULED' ? '승인 안내 · 시행 예정' : '승인 안내 · 시행 중'}</span>{' '}
      <Link to={`/staff/policy-changes/${row.id}`}>{row.summary}</Link> <small>안내 기준 {row.sourceVersion} · {formatDateTime(row.effectiveAt)} 시행{reasons(row)}</small>
      {row.newerBankVersion && <small className="policy-warning"> 은행은 {row.newerBankVersion}을 이미 시행 중이에요. 새 버전 검토가 필요해요.</small>}
    </p>)}
    {notices.pending.map(row => <p key={row.id}>
      <span className="staff-badge staff-badge--warn">{row.effectiveState === 'WITHDRAWN' ? '은행 철회 · 검토 대기' : '미승인 · 미반영'}</span>{' '}
      <Link to={`/staff/policy-changes/${row.id}`}>{row.summary}</Link> <small>{row.sourceVersion} · 기존 기준으로 처리{reasons(row)}</small>
    </p>)}
    <p><small>직원 안내이며 업무 규칙과 Case 상태는 바뀌지 않아요.</small></p>
  </section>
}
