import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { toUserMessageOr } from '../../api/errorMessages'
import {
  CHANGE_TYPE_LABELS, REASON_LABELS, needsReview, policyChangeApi, taskLabel,
  type ChangeView, type OperationEntry, type PolicyAnalysis, type PolicyDecision, type PolicyDetail, type PolicyOverview, type SyncResult,
} from '../../api/policyChangeApi'
import { formatDateTime } from '../c02/c02CaseView'
import { statusLabel } from '../c02/c02StaffText'
import './policy-changes.css'

type Tone = { label: string; tone: string }
const INTAKE_LABELS: Record<string, string> = { ACCEPTED: '정상 수신', DUPLICATE: '중복 수신', STALE_VERSION: '지난 버전', QUARANTINED: '격리됨', NOT_APPLICABLE: '담당 업무 아님' }
const REVIEW_STATUS: Record<string, Tone> = {
  PENDING_REVIEW: { label: '검토 대기', tone: 'warn' }, NOTICE_APPROVED: { label: '안내 반영 승인', tone: 'success' },
  DEV_REQUIRED: { label: '개발 반영 필요', tone: 'info' }, NO_ACTION: { label: '반려 · 안내 미반영', tone: 'muted' },
  SUPERSEDED: { label: '새 버전으로 대체', tone: 'muted' }, STALE_VERSION: { label: '지난 버전 수신', tone: 'muted' },
  NOT_APPLICABLE: { label: '담당 업무 아님', tone: 'muted' },
}
const EFFECTIVE_STATE: Record<string, Tone> = {
  SCHEDULED: { label: '시행 예정', tone: 'info' }, IN_EFFECT: { label: '시행 중', tone: 'success' }, WITHDRAWN: { label: '은행 철회', tone: 'danger' },
}
const DECISIONS: { value: PolicyDecision; label: string; help: string }[] = [
  { value: 'NOTICE_APPROVED', label: '직원 안내에 반영', help: '해당 업무의 직원 화면에 안내합니다.' },
  { value: 'DEV_REQUIRED', label: '개발 반영 필요', help: '규칙이나 절차 변경이 필요하다고 기록합니다.' },
  { value: 'NO_ACTION', label: '반려', help: '직원 안내에 반영하지 않고 기존 안내를 유지합니다.' },
]
const OPERATION_LABELS: Record<string, string> = {
  POLICY_SYNC_COMPLETED: '수신 완료', POLICY_SYNC_FAILED: '수신 실패', POLICY_CHANGE_QUARANTINED: '격리됨',
  POLICY_AGENT_ANALYZED: 'Agent 분석', POLICY_AGENT_ANALYSIS_FAILED: 'Agent 분석 실패', POLICY_REVIEW_DECIDED: '직원 결정',
}

function Badge({ tone }: { tone?: Tone }) {
  return <span className={`staff-badge staff-badge--${tone?.tone ?? 'muted'}`}>{tone?.label ?? '—'}</span>
}

function syncMessage(result: SyncResult) {
  return `${result.fetched}건 확인: 신규 ${result.accepted}건, 중복 ${result.duplicate}건, 지난 버전 ${result.stale}건, 담당 외 ${result.outOfScope}건, 격리 ${result.quarantined}건`
}

function ChangeTable({ rows }: { rows: ChangeView[] }) {
  if (rows.length === 0) return <p className="staff-empty">해당하는 변경정보가 없어요.</p>
  return <div className="staff-table-wrap"><table className="staff-table">
    <thead><tr><th>변경 내용</th><th>유형</th><th>관련 업무</th><th>시행일</th><th>검토 상태</th><th><span className="staff-sr">열기</span></th></tr></thead>
    <tbody>{rows.map(row => <tr key={row.id}>
      <td><b>{row.change.changeSummary}</b><small className="staff-mono">{row.change.externalPolicyId} · {row.change.sourceVersion}</small></td>
      <td>{CHANGE_TYPE_LABELS[row.change.changeType] ?? row.change.changeType}</td>
      <td>{row.mappedTaskCodes.length > 0 ? row.mappedTaskCodes.map(taskLabel).join(', ') : '—'}</td>
      <td>{formatDateTime(row.change.effectiveAt)}<small><Badge tone={EFFECTIVE_STATE[row.effectiveState]} /></small></td>
      <td><Badge tone={REVIEW_STATUS[row.reviewStatus]} /></td>
      <td><Link className="staff-button staff-button--small policy-open" to={`/staff/policy-changes/${row.id}`}>{needsReview(row) ? '검토하기' : '보기'}</Link></td>
    </tr>)}</tbody>
  </table></div>
}

function OperationTable({ rows }: { rows: OperationEntry[] }) {
  if (rows.length === 0) return <p className="staff-empty">수신 기록이 없어요.</p>
  return <div className="staff-table-wrap"><table className="staff-table">
    <thead><tr><th>시각</th><th>구분</th><th>내용</th></tr></thead>
    <tbody>{rows.map(row => <tr key={row.id}>
      <td>{formatDateTime(row.at)}</td>
      <td>{OPERATION_LABELS[row.eventType] ?? row.eventType}</td>
      <td className="staff-mono">{operationText(row)}</td>
    </tr>)}</tbody>
  </table></div>
}

function operationText(row: OperationEntry) {
  if (row.eventType === 'POLICY_CHANGE_QUARANTINED') return `${String(row.payload.changeEventId ?? '식별 불가')}: ${row.value ?? ''}`
  if (row.eventType === 'POLICY_SYNC_FAILED' || row.eventType === 'POLICY_AGENT_ANALYSIS_FAILED') return String(row.payload.code ?? '')
  if (row.eventType === 'POLICY_AGENT_ANALYZED') return row.payload.method === 'LLM' ? `AI 모델 분석 (${String(row.payload.model ?? '모델 미상')})` : '규칙 기반 정리'
  if (row.eventType === 'POLICY_REVIEW_DECIDED') return (REVIEW_STATUS[row.value ?? '']?.label ?? row.value ?? '') + (row.payload.note ? ` · ${String(row.payload.note)}` : '')
  return `신규 ${String(row.payload.accepted ?? 0)}건 / 중복 ${String(row.payload.duplicate ?? 0)}건 / 격리 ${String(row.payload.quarantined ?? 0)}건`
}

const TABS = [
  { key: 'review', label: '검토 필요' }, { key: 'all', label: '전체 변경정보' }, { key: 'operations', label: '수신 기록' },
] as const
type TabKey = (typeof TABS)[number]['key']

export function PolicyChangeListPage() {
  const [data, setData] = useState<PolicyOverview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [syncing, setSyncing] = useState(false)
  const [tab, setTab] = useState<TabKey>('review')

  const load = useCallback(async () => {
    try {
      setData((await policyChangeApi.overview()).data ?? null)
      setError(null)
    } catch (cause) { setError(toUserMessageOr(cause, '변경정보를 불러오지 못했어요.')) }
  }, [])
  useEffect(() => { void load() }, [load])

  const sync = async () => {
    setSyncing(true)
    setMessage(null)
    try {
      const result = (await policyChangeApi.sync()).data
      if (result) setMessage(syncMessage(result))
      setError(null)
    } catch (cause) { setError(toUserMessageOr(cause, '은행 변경정보를 가져오지 못했어요. 이미 받은 내용은 그대로 있어요.')) }
    await load()
    setSyncing(false)
  }

  const pending = data?.changes.filter(needsReview) ?? []
  return <div className="staff-page">
    <div className="staff-page__head">
      <div><h1>업무기준 변경</h1><span>은행 업무정보 시스템이 제공한 변경정보를 확인하고 반영 여부를 기록해요.</span></div>
      <div className="staff-page__actions"><button type="button" className="staff-button" disabled={syncing} onClick={() => void sync()}>{syncing ? '가져오는 중…' : '은행 변경정보 가져오기'}</button></div>
    </div>
    {message && <p className="policy-note" role="status">{message}</p>}
    {error && <p className="staff-alert" role="alert">{error}</p>}
    <section className="staff-card">
      <div className="staff-tabs" role="tablist">{TABS.map(item => <button key={item.key} type="button" role="tab" aria-selected={tab === item.key} className={tab === item.key ? 'is-active' : ''} onClick={() => setTab(item.key)}>{item.label}{item.key === 'review' && data ? ` ${pending.length}` : ''}</button>)}</div>
      {!data && !error && <p className="staff-empty">불러오는 중이에요.</p>}
      {data && tab === 'review' && <ChangeTable rows={pending} />}
      {data && tab === 'all' && <ChangeTable rows={data.changes} />}
      {data && tab === 'operations' && <OperationTable rows={data.operations} />}
    </section>
  </div>
}

function FactsCard({ view }: { view: ChangeView }) {
  const { change } = view
  return <section className="staff-card">
    <div className="staff-card__head"><h2>은행 제공 정보</h2><span>{change.sourceSystem} 원문 · Banking-AX가 수정하지 않아요</span></div>
    <dl className="policy-facts">
      <div><dt>기준 ID</dt><dd className="staff-mono">{change.externalPolicyId}</dd></div>
      <div><dt>버전</dt><dd className="staff-mono">{change.previousVersion ?? '없음'} → {change.sourceVersion}</dd></div>
      <div><dt>변경 유형</dt><dd>{CHANGE_TYPE_LABELS[change.changeType] ?? change.changeType}</dd></div>
      <div><dt>은행 상태</dt><dd><Badge tone={EFFECTIVE_STATE[view.effectiveState]} /></dd></div>
      <div><dt>공표</dt><dd>{formatDateTime(change.publishedAt)}</dd></div>
      <div><dt>시행</dt><dd>{formatDateTime(change.effectiveAt)}</dd></div>
      <div><dt>근거 문서</dt><dd>{change.sourceReference.title ?? change.sourceReference.documentId} <small className="staff-mono">{change.sourceReference.documentId}</small></dd></div>
      <div><dt>은행 지정 업무</dt><dd>{view.mappedTaskCodes.map(taskLabel).join(', ') || '—'}{view.unmappedTaskCodes.length > 0 && <small> · 미등록: {view.unmappedTaskCodes.join(', ')}</small>}</dd></div>
    </dl>
    <div className="policy-compare">
      <div><h3>변경 전</h3><p>{change.detail.before || '은행이 제공하지 않았어요.'}</p></div>
      <div><h3>변경 후</h3><p>{change.detail.after}</p></div>
    </div>
    {(change.detail.addedCheckItems?.length ?? 0) > 0 && <><h3 className="policy-subhead">추가 확인 항목</h3><ul className="policy-list">{change.detail.addedCheckItems?.map(item => <li key={item}>{item}</li>)}</ul></>}
  </section>
}

function ImpactCard({ detail }: { detail: PolicyDetail }) {
  return <section className="staff-card">
    <div className="staff-card__head"><h2>영향 가능 Case</h2><span>재검토 후보이며 Case는 바뀌지 않아요</span></div>
    {detail.impact.map(task => <div key={task.taskCode}>
      <p className="policy-note">{taskLabel(task.taskCode)}: 진행 중 {task.openCaseCount}건 중 후보 {task.candidates.length}건</p>
      {task.candidates.length > 0 && <div className="staff-table-wrap"><table className="staff-table">
        <thead><tr><th>Case</th><th>상태</th><th>접수 일시</th><th>접수 당시 기준</th><th>후보 사유</th></tr></thead>
        <tbody>{task.candidates.map(row => <tr key={row.caseId}>
          <td>{task.taskCode === 'INHERITANCE_DEPOSIT' ? <Link to={`/staff/c02/${row.caseId}`}>#{row.caseId}</Link> : `#${row.caseId}`}<small>r{row.revision}</small></td>
          <td>{statusLabel(row.caseStatus)}</td>
          <td>{formatDateTime(row.submittedAt)}<small>{row.submittedBeforeEffective ? '시행일 전 접수' : '시행일 이후 접수'}</small></td>
          <td className="staff-mono">{row.ruleVersion ?? '기록 없음'}<small>수신 기준 {row.policyVersionAtSubmission ?? '없음'}</small></td>
          <td>{row.reasonCodes.map(code => REASON_LABELS[code] ?? code).join(', ')}</td>
        </tr>)}</tbody>
      </table></div>}
    </div>)}
  </section>
}

function AnalysisBody({ analysis }: { analysis: PolicyAnalysis }) {
  if (analysis.status === 'FAILED') return <p className="staff-alert" role="alert">분석에 실패했어요. 은행 제공 정보는 그대로이며 다시 시도하거나 직접 검토할 수 있어요.</p>
  return <>
    <p className="policy-note">{analysis.method === 'LLM' ? `AI 모델 분석 (${analysis.model ?? '모델 미상'})` : 'AI 모델 없이 규칙으로 정리'} · {formatDateTime(analysis.analyzedAt)}{analysis.droppedClaims ? ` · 근거 밖 내용 ${analysis.droppedClaims}건 제외` : ''}</p>
    <p>{analysis.summary}</p>
    {(analysis.changePoints?.length ?? 0) > 0 && <><h3 className="policy-subhead">달라지는 점</h3><ul className="policy-list">{analysis.changePoints?.map(point => <li key={point.text}>{point.text}</li>)}</ul></>}
    {(analysis.relatedTasks?.length ?? 0) > 0 && <><h3 className="policy-subhead">관련 업무</h3><ul className="policy-list">{analysis.relatedTasks?.map(task => <li key={task.taskCode}><b>{taskLabel(task.taskCode)}</b> <span className={`staff-badge staff-badge--${task.bankDesignated ? 'info' : 'warn'}`}>{task.bankDesignated ? '은행 지정' : 'AI 제안 · 미확정'}</span> {task.reason}</li>)}</ul></>}
    {(analysis.recheckItems?.length ?? 0) > 0 && <><h3 className="policy-subhead">다시 확인할 항목</h3><ul className="policy-list">{analysis.recheckItems?.map(item => <li key={item.text}>{item.text}{item.caseIds.length > 0 && <small className="staff-mono"> Case {item.caseIds.map(id => `#${id}`).join(', ')}</small>}</li>)}</ul></>}
    {analysis.customerNoticeHint && <><h3 className="policy-subhead">고객 안내 가능성</h3><p>{analysis.customerNoticeHint}</p></>}
    {(analysis.evidence?.length ?? 0) > 0 && <><h3 className="policy-subhead">현행 업무 근거 (변경 내용의 근거 아님)</h3><ul className="policy-list">{analysis.evidence?.map(row => <li key={`${row.docId}-${row.section}`}><span className="staff-mono">{row.docId}</span> {row.title} · {row.section}</li>)}</ul></>}
  </>
}

function ReviewCard({ detail, busy, onDecide }: { detail: PolicyDetail; busy: boolean; onDecide: (decision: PolicyDecision, note: string) => void }) {
  const [note, setNote] = useState('')
  const { view } = detail
  return <section className="staff-card">
    <div className="staff-card__head"><h2>직원 검토</h2><span>승인해도 업무 규칙과 기존 Case는 바뀌지 않아요</span></div>
    {view.decision
      ? <p className="policy-note"><Badge tone={REVIEW_STATUS[view.decision.decision]} /> {formatDateTime(view.decision.decidedAt)} 기록{view.decision.note ? ` · ${view.decision.note}` : ''}</p>
      : view.reviewStatus !== 'PENDING_REVIEW'
        ? <p className="policy-note">이 버전은 검토 대상이 아니에요. <Badge tone={REVIEW_STATUS[view.reviewStatus]} /></p>
        : <>
          <label className="policy-label" htmlFor="policy-note">검토 메모 (고객 개인정보는 적지 마세요)</label>
          <textarea id="policy-note" className="policy-textarea" rows={2} maxLength={500} value={note} onChange={event => setNote(event.target.value)} />
          <div className="policy-decisions">{DECISIONS.map(item => <button key={item.value} type="button" title={item.help} disabled={busy} className={`staff-button${item.value === 'NOTICE_APPROVED' ? '' : ' staff-button--secondary'}`} onClick={() => onDecide(item.value, note)}>{item.label}</button>)}</div>
        </>}
  </section>
}

export function PolicyChangeDetailPage() {
  const id = Number(useParams().id)
  const [detail, setDetail] = useState<PolicyDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      setDetail((await policyChangeApi.detail(id)).data ?? null)
      setError(null)
    } catch (cause) { setError(toUserMessageOr(cause, '변경정보를 불러오지 못했어요.')) }
  }, [id])
  useEffect(() => { void load() }, [load])

  const run = async (action: () => Promise<unknown>, failure: string) => {
    setBusy(true)
    try { await action() } catch (cause) { setError(toUserMessageOr(cause, failure)) }
    await load()
    setBusy(false)
  }

  const view = detail?.view
  return <div className="staff-page">
    <div className="staff-page__head">
      <div><h1>{view?.change.changeSummary ?? '업무기준 변경'}</h1>{view && <span><Badge tone={REVIEW_STATUS[view.reviewStatus]} /></span>}</div>
      <div className="staff-page__actions"><Link to="/staff/policy-changes" className="staff-button staff-button--secondary">목록</Link></div>
    </div>
    {error && <p className="staff-alert" role="alert">{error}</p>}
    {detail && view && <>
      {view.change.detail.fictional && <p className="policy-banner">교육용 가상 사례입니다. 실제 은행의 규정 변경이 아니에요.</p>}
      {view.ruleChangeReviewRequired && <p className="policy-banner policy-banner--warn">적용 검토 및 개발 반영 필요: 이 변경은 안내 승인만으로 업무 규칙에 반영되지 않아요.</p>}
      {view.versionGap && <p className="policy-banner policy-banner--warn">이전 버전을 건너뛰고 수신했어요. 은행 측 변경 이력을 확인해 주세요.</p>}
      <p className="policy-zone"><b>1</b>은행이 보낸 내용과 영향 범위</p>
      <FactsCard view={view} />
      <ImpactCard detail={detail} />
      <p className="policy-zone"><b>2</b>AI 참고 분석</p>
      <section className="staff-card policy-ai-card">
        <div className="staff-card__head"><h2>직원 Agent 분석</h2><span>AI 해석 · 판정이 아니에요</span></div>
        <p className="policy-note">아래 내용은 AI가 해석한 참고 자료이며 틀릴 수 있어요. 기준은 위의 은행 제공 정보를 확인해 주세요.</p>
        {detail.analysis ? <AnalysisBody analysis={detail.analysis} /> : <p className="staff-empty">아직 분석하지 않았어요.</p>}
        {view.reviewStatus === 'PENDING_REVIEW' && <button type="button" className="staff-button staff-button--secondary" disabled={busy} onClick={() => void run(() => policyChangeApi.analyze(id), 'Agent 분석을 요청하지 못했어요.')}>{busy ? '처리 중…' : detail.analysis ? '다시 분석' : 'Agent 분석 실행'}</button>}
      </section>
      <p className="policy-zone"><b>3</b>직원 결정</p>
      <div className="policy-decide"><ReviewCard detail={detail} busy={busy} onDecide={(decision, note) => void run(() => policyChangeApi.decide(id, decision, note), '검토 결과를 저장하지 못했어요.')} /></div>
      <p className="policy-zone policy-zone--plain">기록</p>
      <div className="policy-history"><section className="staff-card">
        <div className="staff-card__head"><h2>버전 이력</h2><span>수신한 전체 버전</span></div>
        <div className="staff-table-wrap"><table className="staff-table">
          <thead><tr><th>버전</th><th>수신 결과</th><th>시행일</th><th>수신 일시</th></tr></thead>
          <tbody>{detail.versions.map(row => <tr key={row.id}><td className="staff-mono">{row.sourceVersion}</td><td>{INTAKE_LABELS[row.intakeStatus] ?? row.intakeStatus}{row.versionGap ? ' · 버전 건너뜀' : ''}</td><td>{formatDateTime(row.effectiveAt)}</td><td>{formatDateTime(row.receivedAt)}</td></tr>)}</tbody>
        </table></div>
      </section>
      <section className="staff-card">
        <div className="staff-card__head"><h2>검토 이력</h2></div>
        <OperationTable rows={detail.audit.map(row => ({ ...row, payload: { ...row.payload } }))} />
      </section></div>
    </>}
  </div>
}
