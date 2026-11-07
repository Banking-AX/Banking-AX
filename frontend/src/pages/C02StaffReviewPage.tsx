import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { c02Api, taskBffEnabled, type C02Document, type C02DocumentSlot, type C02OcrSnapshot, type C02Review, type C02StaffCase } from '../api/taskApi'
import { toUserMessageOr } from '../api/errorMessages'
import { c02DocumentLabel } from '../ui/customerLabels'
import { hasCurrentReview } from '../features/c02/c02CaseView'
import { C02AiReviewCard } from '../features/c02/C02AiReview'
import { C02OcrResults } from '../features/c02/C02OcrResults'
import { isOcrItem, ocrStatusLabel } from '../features/c02/c02OcrView'
import { agentItemView, decisionGuide, runLabel, staffSummaryLines, statusLabel } from '../features/c02/c02StaffText'
import { PolicyNoticePanel } from '../features/staff/PolicyNoticePanel'
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
  return <span className={`c02-staff-badge c02-staff-badge--${statusTone(value)}`} title={value ?? undefined}>{statusLabel(value)}</span>
}

function documentForSlot(documents: C02Document[], slot: C02DocumentSlot) {
  return documents.filter(document => document.documentSlotKey === `${slot.documentTypeCode}:${slot.personRef ?? 'COMMON'}`)
}

function OcrStatus({ files, slot, snapshot }: { files: C02Document[]; slot: C02DocumentSlot; snapshot: C02OcrSnapshot | null }) {
  const latest = files.reduce<C02Document | null>((newest, file) => !newest || file.version > newest.version ? file : newest, null)
  return <span className="c02-staff-muted">{latest ? ocrStatusLabel(latest, snapshot) : slot.requirement === 'VISIT_ONLY' ? '영업점 방문 시 확인' : '제출 전'}</span>
}

/** One line of progress beside the decision, so the next step is visible without scrolling. */
function ReviewSteps({ data, reviewed }: { data: C02StaffCase; reviewed: boolean }) {
  const decided = Boolean(data.finalDecision)
  const started = decided || data.caseView.caseStatus === 'IN_REVIEW'
  const steps = [{ label: '사전점검', done: reviewed || started }, { label: '검토 진행', done: started }, { label: '결과 등록', done: decided }]
  const next = steps.findIndex(step => !step.done)
  return <ol className="c02-staff-steps" aria-label="검토 진행 단계">{steps.map((step, index) => <li key={step.label} className={step.done ? 'is-done' : index === next ? 'is-current' : ''} aria-current={index === next ? 'step' : undefined}><i aria-hidden="true">{step.done ? '✓' : index + 1}</i>{step.label}</li>)}</ol>
}

function CasePrecheckSummary({ data }: { data: C02StaffCase }) {
  const summary = data.summary
  return <section className="c02-staff-card c02-staff-card--wide"><div className="c02-staff-card-head"><div><span className="c02-staff-kicker">접수·사전검토 요약</span><h2>직원이 먼저 확인할 사항</h2></div></div>
    <p className="c02-staff-meta">상속인 {summary?.heirCount ?? data.intake?.heirs.length ?? 0}명 · 미방문 {summary?.absentHeirCount ?? 0}명 · 필수서류 {summary?.uploadedRequiredCount ?? 0}/{summary?.requiredUploadCount ?? 0}건 · 방문 시 확인 {summary?.visitOnlyCount ?? 0}건</p>
    <ul className="c02-staff-review-list c02-staff-review-list--summary">{staffSummaryLines(data).map(line => <li key={line.title + line.body} className={line.warning ? 'c02-staff-review-warning' : undefined}><strong>{line.title}</strong><p>{line.body}</p></li>)}</ul>
  </section>
}

function ReviewItems({ review }: { review: C02Review }) {
  const agentItems = review.reviewItems.filter(item => !isOcrItem(item))
  if (agentItems.length === 0) return <p className="c02-staff-empty">현재 버전의 사전점검 항목이 없습니다. 항목이 없어도 직원이 원본을 확인해야 합니다.</p>
  return <ul className="c02-staff-review-list">{agentItems.map((item, index) => {
    const view = agentItemView(item)
    return <li key={`${item.category ?? 'item'}-${index}`}><strong>{view.title}</strong><p>{view.body}</p><span>출처: {view.source}</span>
      <details className="c02-staff-detail"><summary>원본 코드 보기</summary><code>{item.category ?? '-'} / {item.result ?? '-'} / {item.source ?? '-'}</code></details></li>
  })}</ul>
}
/** Staff actions are possible only behind a staff-session BFF; Agent output remains reference material. */
export function C02StaffReviewPage() {
  const { caseId } = useParams()
  const parsedId = Number(caseId)
  const [data, setData] = useState<C02StaffCase | null>(null)
  const [ocr, setOcr] = useState<C02OcrSnapshot | null>(null)
  const [outcome, setOutcome] = useState<Outcome | ''>('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = async () => {
    if (!Number.isSafeInteger(parsedId) || parsedId <= 0) { setError('Case ID가 올바르지 않습니다.'); return }
    setBusy(true); setError(null); setNotice(null)
    try {
      const response = await c02Api.staffCase(parsedId, requestId('case', parsedId))
      const caseData = response.data ?? null
      setData(caseData)
      if (caseData) {
        try { const ocrResponse = await c02Api.staffOcr(parsedId, caseData.caseView.revision, requestId('ocr', parsedId)); setOcr(ocrResponse.data ?? null) }
        catch { setOcr(null) }
      } else setOcr(null)
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
    if (!outcome) { setError('검토 결과를 선택해 주세요.'); return }
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

  /** A manually written message is replaced only after staff agree; regenerating never touches it. */
  const applyDraft = (draft: string) => {
    if (message.trim() && message !== draft && !window.confirm('작성 중인 고객 안내를 초안으로 바꿀까요?')) return
    setMessage(draft)
  }

  const current = data?.caseView
  const currentReview = hasCurrentReview(data?.precheck, current?.revision)
  const submitted = current?.caseStatus === 'SUBMITTED'
  const canPrecheck = Boolean(taskBffEnabled && current && submitted && !data?.finalDecision && !currentReview && !busy)
  // 검토 시작 locks out customer cancellation; the result can only be registered after that (spec 3-2).
  const canStart = Boolean(taskBffEnabled && current && submitted && currentReview && !data?.finalDecision && !busy)
  const canDecide = Boolean(taskBffEnabled && current && current.caseStatus === 'IN_REVIEW' && currentReview && !data?.finalDecision && !busy && outcome)

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
        <div><span>Case</span><strong>#{current.caseId}</strong></div><div><span>업무 번호</span><strong>#{current.workItemId}</strong></div><div><span>접수 버전</span><strong>r{current.revision}</strong></div><div><span>Case 상태</span><StatusBadge value={current.caseStatus} /></div><div><span>사전점검 상태</span><StatusBadge value={current.reviewStatus} /></div>
      </section>
      {current.caseStatus === 'DRAFT' && <p className="c02-staff-banner">현재 Case는 DRAFT입니다. 제출 전에는 직원 사전점검과 최종결정을 실행할 수 없습니다.</p>}
      <PolicyNoticePanel taskCode="INHERITANCE_DEPOSIT" caseId={current.caseId} />
      <div className="c02-staff-workspace"><div className="c02-staff-main">
        <CasePrecheckSummary data={data} />
        <C02AiReviewCard caseId={current.caseId} revision={current.revision} canGenerate={Boolean(!data.finalDecision && (submitted || current.caseStatus === 'IN_REVIEW'))} onApply={data.finalDecision ? null : applyDraft} />
        <section className="c02-staff-card c02-staff-card--wide"><div className="c02-staff-card-head"><div><span className="c02-staff-kicker">위임장 자동 대조</span><h2>신청정보와 OCR 추출값</h2></div></div>
          <C02OcrResults ocr={ocr} intake={data.intake} items={data.precheck?.reviewItems ?? []} />
        </section>
        <section className="c02-staff-card c02-staff-card--wide"><div className="c02-staff-card-head"><div><span className="c02-staff-kicker">문서 현황</span><h2>필수 서류와 제출 상태</h2></div><span>{data.requiredDocuments.length}개 요구</span></div>
          {data.requiredDocuments.length === 0 ? <p className="c02-staff-empty">등록된 필수 서류가 없습니다. 누락으로 추정하지 말고 Case 입력과 업무 규칙을 확인해 주세요.</p> : <div className="c02-staff-table-wrap"><table><thead><tr><th>서류</th><th>제출 파일</th><th>상태</th><th>자동 판독</th></tr></thead><tbody>{[...data.requiredDocuments].sort((a, b) => Number(a.requirement === 'VISIT_ONLY') - Number(b.requirement === 'VISIT_ONLY')).map(slot => { const files = documentForSlot(data.documents, slot); return <tr key={`${slot.documentTypeCode}:${slot.personRef ?? 'COMMON'}`} className={slot.requirement === 'VISIT_ONLY' ? 'c02-staff-row-muted' : undefined}><td><strong>{c02DocumentLabel(slot.documentTypeCode)}</strong><span>{slot.note}</span></td><td>{files.length === 0 ? <span className="c02-staff-muted">제출된 파일 없음</span> : files.map(file => <span className="c02-staff-file" key={file.documentFileId}><span className="c02-staff-file__name" title={file.fileName}>{file.fileName}<small>접수 버전 r{file.caseRevision} · 파일 v{file.version}</small></span><button type="button" className="c02-staff-link" onClick={() => void openOriginal(file)}>원본 보기</button></span>)}</td><td>{files.length === 0 ? <StatusBadge value="NOT_SUBMITTED" /> : files.map(file => <StatusBadge key={file.documentFileId} value={file.status} />)}</td><td><OcrStatus files={files} slot={slot} snapshot={ocr} /></td></tr> })}</tbody></table></div>}
        </section>
        <section className="c02-staff-card"><div className="c02-staff-card-head"><div><span className="c02-staff-kicker">직원 Agent 안내</span><h2>자동 사전점검 결과</h2></div>{data.precheck && <StatusBadge value={data.precheck.reviewStatus} />}</div>
          {!data.precheck ? <p className="c02-staff-empty">사전점검 결과가 없습니다.</p> : <><p className="c02-staff-meta">검토 기준 r{data.precheck.revision} · {runLabel(data.precheck.agentRunStatus)} · 규칙 기반 안내이며 직원 판단을 대신하지 않습니다</p>{data.precheck.revision !== current.revision && <p className="c02-staff-banner c02-staff-banner--error">이 결과는 이전 revision 기준입니다. 현재 revision으로 다시 점검해야 합니다.</p>}{data.precheck.error && <p className="c02-staff-banner c02-staff-banner--error">Agent 오류가 기록되어 있습니다. 자동 통과로 간주하지 마세요.</p>}<ReviewItems review={data.precheck} /></>}
          <button type="button" className="c02-staff-button-secondary" disabled={!canPrecheck} onClick={() => void precheck()}>사전점검 실행</button>
        </section>
        </div><aside className="c02-staff-aside" aria-label="직원 결과 등록">
        <section className="c02-staff-card c02-staff-card--decision"><div className="c02-staff-card-head"><div><span className="c02-staff-kicker">최종 판단</span><h2>직원 결과 등록</h2></div>{data.finalDecision && <StatusBadge value={data.finalDecision.outcome} />}</div>
          <ReviewSteps data={data} reviewed={Boolean(currentReview)} />
          {data.finalDecision ? <p className="c02-staff-banner c02-staff-banner--success">{decisionGuide(data, outcome)}</p> : <>{submitted ? <><p className="c02-staff-meta">{decisionGuide(data, outcome)} 검토를 시작하면 고객은 접수를 취소할 수 없어요.</p><button type="button" disabled={!canStart} onClick={() => void startReview()}>검토 시작</button></> : <p className="c02-staff-meta">{decisionGuide(data, outcome)}</p>}<label>검토 결과<select value={outcome} onChange={event => setOutcome(event.target.value as Outcome | '')}><option value="">검토 결과를 선택해 주세요</option><option value="PROCEED">진행 가능</option><option value="REQUIREMENT_FAILED">요건 미충족</option><option value="DOCUMENT_INSUFFICIENT">서류 보완 필요</option></select></label><label>고객 안내<textarea value={message} onChange={event => setMessage(event.target.value)} placeholder="검토 결과와 필요한 다음 조치를 작성하세요." /></label><button type="button" disabled={!canDecide} onClick={() => void decide()}>검토 결과 저장 및 고객 안내</button></>}
        </section>
      </aside></div>
    </>}
  </section>
}
