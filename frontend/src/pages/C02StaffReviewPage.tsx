import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { c02Api, taskBffEnabled, type C02Document, type C02DocumentSlot, type C02Review, type C02StaffCase } from '../api/taskApi'
import { toUserMessageOr } from '../api/errorMessages'
import { c02DocumentLabel } from '../ui/customerLabels'
import '../features/c02/staff-review.css'

type Outcome = 'PROCEED' | 'REQUIREMENT_FAILED' | 'DOCUMENT_INSUFFICIENT'
const requestId = (action: string, caseId: number) => `c02-staff-${action}-${caseId}-${crypto.randomUUID()}`

function statusTone(status: string | null | undefined): string {
  if (!status) return 'neutral'
  if (['REVIEW_PASSED', 'PROCEED', 'READY_FOR_STAFF', 'SUCCEEDED', 'UPLOADED'].includes(status)) return 'success'
  if (['FAILED_NEEDS_STAFF', 'REQUIREMENT_FAILED'].includes(status)) return 'danger'
  if (['WAITING_CUSTOMER', 'DOCUMENT_INSUFFICIENT'].includes(status)) return 'caution'
  if (['SUBMITTED', 'RUNNING', 'IN_REVIEW'].includes(status)) return 'progress'
  return 'neutral'
}

function StatusBadge({ value }: { value: string | null | undefined }) {
  return <span className={`c02-staff-badge c02-staff-badge--${statusTone(value)}`}>{value ?? '미확인'}</span>
}

function documentForSlot(documents: C02Document[], slot: C02DocumentSlot) {
  return documents.filter(document => document.documentSlotKey === `${slot.documentTypeCode}:${slot.personRef ?? 'COMMON'}`)
}

function ReviewItems({ review }: { review: C02Review }) {
  if (review.reviewItems.length === 0) return <p className="c02-staff-empty">현재 revision의 사전점검 항목이 없습니다. 결과가 없다는 뜻이 아니라, 직원이 원본과 현재 상태를 확인해야 합니다.</p>
  return <ul className="c02-staff-review-list">{review.reviewItems.map((item, index) => <li key={`${item.category ?? 'item'}-${index}`}>
    <strong>{item.category === 'POLICY_EVIDENCE' ? '업무 근거 (참고)' : item.category ?? '검토 항목'}</strong><p>{item.message ?? '상세 메시지가 제공되지 않았습니다.'}</p>
    {item.source && <span>근거: {item.source}</span>}
  </li>)}</ul>
}

/** Staff actions are possible only behind a staff-session BFF; Agent output remains reference material. */
export function C02StaffReviewPage() {
  const { caseId } = useParams()
  const parsedId = Number(caseId)
  const [data, setData] = useState<C02StaffCase | null>(null)
  const [outcome, setOutcome] = useState<Outcome>('PROCEED')
  const [message, setMessage] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = async () => {
    if (!Number.isSafeInteger(parsedId) || parsedId <= 0) { setError('Case ID가 올바르지 않습니다.'); return }
    setBusy(true); setError(null); setNotice(null)
    try {
      const response = await c02Api.staffCase(parsedId, requestId('case', parsedId))
      setData(response.data ?? null)
    } catch (cause) {
      setError(toUserMessageOr(cause, '직원 Case를 불러오지 못했습니다.'))
    } finally { setBusy(false) }
  }

  // Open the Case as soon as the staff member arrives from the queue.
  useEffect(() => { if (taskBffEnabled) void load() }, [parsedId]) // eslint-disable-line react-hooks/exhaustive-deps

  // The window opens synchronously so the popup blocker allows it; the authenticated file is loaded afterwards.
  const openOriginal = async (file: C02Document) => {
    const target = window.open('', '_blank')
    try {
      const blob = await c02Api.staffDocument(parsedId, file.documentFileId)
      const url = URL.createObjectURL(blob)
      if (target) target.location.href = url; else window.location.href = url
      setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch (cause) { target?.close(); setError(toUserMessageOr(cause, '원본 파일을 열지 못했습니다.')) }
  }

  const startReview = async () => {
    const current = data?.caseView
    if (!current) return
    setBusy(true); setError(null); setNotice(null)
    try {
      await c02Api.startReview(parsedId, { revision: current.revision, requestId: requestId('start', parsedId) })
      setNotice('검토를 시작했어요. 이제 고객은 접수를 취소할 수 없어요.')
      await load()
    } catch (cause) {
      setError(toUserMessageOr(cause, '검토를 시작하지 못했습니다.'))
    } finally { setBusy(false) }
  }

  const precheck = async () => {
    const current = data?.caseView
    if (!current || !Number.isSafeInteger(parsedId)) { setError('최신 Case를 먼저 조회해 주세요.'); return }
    setBusy(true); setError(null); setNotice(null)
    try {
      await c02Api.staffPrecheck(parsedId, { revision: current.revision, requestId: requestId('precheck', parsedId) })
      await load()
    } catch (cause) {
      setError(toUserMessageOr(cause, '직원 Agent 사전점검을 실행하지 못했습니다.'))
    } finally { setBusy(false) }
  }

  const decide = async () => {
    const current = data?.caseView
    const review = data?.precheck
    if (!current || !review || review.revision !== current.revision || !Number.isSafeInteger(parsedId)) {
      setError('현재 Case revision의 사전점검 결과를 먼저 확인해 주세요.')
      return
    }
    setBusy(true); setError(null); setNotice(null)
    try {
      const response = await c02Api.staffDecision(parsedId, { revision: current.revision, requestId: requestId('decision', parsedId), outcome, customerMessage: message })
      const notification = response.data?.notification
      setNotice(notification ? '검토 결과를 등록하고 고객에게 웹 알림을 보냈어요.' : '검토 결과를 등록했어요.')
      await load()
    } catch (cause) {
      setError(toUserMessageOr(cause, '최종결정을 저장할 수 없습니다.'))
    } finally { setBusy(false) }
  }

  const current = data?.caseView
  const currentReview = Boolean(current && data?.precheck?.revision === current.revision)
  const submitted = current?.caseStatus === 'SUBMITTED'
  const canPrecheck = Boolean(taskBffEnabled && current && submitted && !data?.finalDecision && !currentReview && !busy)
  // 검토 시작 locks out customer cancellation; the result can only be registered after that (spec 3-2).
  const canStart = Boolean(taskBffEnabled && current && submitted && currentReview && !data?.finalDecision && !busy)
  const canDecide = Boolean(taskBffEnabled && current && current.caseStatus === 'IN_REVIEW' && currentReview && !data?.finalDecision && !busy)

  return <section className="c02-staff-page">
    <header className="c02-staff-hero">
      <div><span className="c02-staff-kicker">C-02 · 직원 검토</span><h1>상속예금 사전접수 검토</h1><p>Agent와 OCR 결과는 참고 자료입니다. 서류 원본, 권리관계와 최종 모의 접수 검토 결과는 직원이 직접 확인·기록합니다.</p></div>
      <div className="c02-staff-actions"><button type="button" disabled={!taskBffEnabled || busy} onClick={() => void load()}>{busy ? '처리 중…' : '최신 Case 조회'}</button><Link to="/staff/queue">사전검토 업무함</Link></div>
    </header>
    {!taskBffEnabled && <p className="c02-staff-banner" role="status">직원 로그인 BFF 연결이 비활성화되어 있습니다. 화면은 내부 gateway token을 사용하지 않습니다.</p>}
    {error && <p className="c02-staff-banner c02-staff-banner--error" role="alert">{error}</p>}
    {notice && <p className="c02-staff-banner c02-staff-banner--success" role="status">{notice} <Link to="/staff/queue">업무함으로 돌아가기</Link></p>}
    {!data && !error && <section className="c02-staff-empty-card"><h2>검토할 Case를 조회해 주세요</h2><p>Case ID와 직원 권한을 확인한 뒤 현재 revision, 문서와 사전점검 결과를 표시합니다.</p></section>}
    {current && <>
      <section className="c02-staff-summary" aria-label="Case 요약">
        <div><span>Case</span><strong>#{current.caseId}</strong></div><div><span>WorkItem</span><strong>#{current.workItemId}</strong></div><div><span>현재 revision</span><strong>r{current.revision}</strong></div><div><span>Case 상태</span><StatusBadge value={current.caseStatus} /></div><div><span>AI 검토 상태</span><StatusBadge value={current.reviewStatus} /></div>
      </section>
      {current.caseStatus === 'DRAFT' && <p className="c02-staff-banner">현재 Case는 DRAFT입니다. 제출 전에는 직원 사전점검과 최종결정을 실행할 수 없습니다.</p>}
      <div className="c02-staff-grid">
        <section className="c02-staff-card c02-staff-card--wide"><div className="c02-staff-card-head"><div><span className="c02-staff-kicker">문서 현황</span><h2>필수 서류와 제출 상태</h2></div><span>{data.requiredDocuments.length}개 요구</span></div>
          {data.requiredDocuments.length === 0 ? <p className="c02-staff-empty">등록된 필수 서류가 없습니다. 누락으로 추정하지 말고 Case 입력과 업무 규칙을 확인해 주세요.</p> : <div className="c02-staff-table-wrap"><table><thead><tr><th>서류</th><th>제출 파일 · revision</th><th>상태</th><th>OCR</th></tr></thead><tbody>{data.requiredDocuments.map(slot => { const files = documentForSlot(data.documents, slot); return <tr key={`${slot.documentTypeCode}:${slot.personRef ?? 'COMMON'}`}><td><strong>{c02DocumentLabel(slot.documentTypeCode)}</strong><span>{slot.note}</span></td><td>{files.length === 0 ? <span className="c02-staff-muted">제출된 파일 없음</span> : files.map(file => <span className="c02-staff-file" key={file.documentFileId}>{file.fileName} · r{file.caseRevision} · v{file.version} <button type="button" className="c02-staff-link" onClick={() => void openOriginal(file)}>원본 보기</button></span>)}</td><td>{files.length === 0 ? <StatusBadge value="NOT_SUBMITTED" /> : files.map(file => <StatusBadge key={file.documentFileId} value={file.status} />)}</td><td><span className="c02-staff-muted">직원 Case API에 OCR 원문/상태가 제공되지 않습니다. 원본을 직접 확인해 주세요.</span></td></tr> })}</tbody></table></div>}
        </section>
        <section className="c02-staff-card"><div className="c02-staff-card-head"><div><span className="c02-staff-kicker">직원 Agent</span><h2>사전점검 결과</h2></div>{data.precheck && <StatusBadge value={data.precheck.reviewStatus} />}</div>
          {!data.precheck ? <p className="c02-staff-empty">사전점검 결과가 없습니다.</p> : <><p className="c02-staff-meta">검토 revision r{data.precheck.revision} · 실행 상태 {data.precheck.agentRunStatus}</p>{data.precheck.revision !== current.revision && <p className="c02-staff-banner c02-staff-banner--error">이 결과는 이전 revision 기준입니다. 현재 revision으로 다시 점검해야 합니다.</p>}{data.precheck.error && <p className="c02-staff-banner c02-staff-banner--error">Agent 오류가 기록되어 있습니다. 자동 통과로 간주하지 마세요.</p>}<ReviewItems review={data.precheck} /></>}
          <button type="button" className="c02-staff-button-secondary" disabled={!canPrecheck} onClick={() => void precheck()}>현재 revision 사전점검 실행</button>
        </section>
        <section className="c02-staff-card"><div className="c02-staff-card-head"><div><span className="c02-staff-kicker">최종 판단</span><h2>직원 결과 등록</h2></div>{data.finalDecision && <StatusBadge value={data.finalDecision.outcome} />}</div>
          {data.finalDecision ? <p className="c02-staff-banner c02-staff-banner--success">최종결정이 r{data.finalDecision.revision}에 이미 기록되었습니다. 같은 Case에 중복 결정을 저장하지 않습니다.</p> : <>{submitted ? <><p className="c02-staff-meta">검토를 시작하면 고객은 접수를 취소할 수 없어요. 현재 revision의 사전점검 결과가 있어야 시작할 수 있어요.</p><button type="button" disabled={!canStart} onClick={() => void startReview()}>검토 시작</button></> : <p className="c02-staff-meta">검토 중 · 서류 원본을 확인한 뒤 결과를 등록하세요.</p>}<label>검토 결과<select value={outcome} onChange={event => setOutcome(event.target.value as Outcome)}><option value="PROCEED">진행 가능</option><option value="REQUIREMENT_FAILED">요건 미충족</option><option value="DOCUMENT_INSUFFICIENT">서류 보완 필요</option></select></label><label>고객 안내<textarea value={message} onChange={event => setMessage(event.target.value)} placeholder="검토 결과와 필요한 다음 조치를 작성하세요." /></label><button type="button" disabled={!canDecide} onClick={() => void decide()}>최종결정 및 고객 알림 저장</button></>}
        </section>
      </div>
    </>}
  </section>
}
