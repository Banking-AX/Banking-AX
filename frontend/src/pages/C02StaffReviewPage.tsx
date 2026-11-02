import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { c02Api, taskBffEnabled, type C02Document, type C02DocumentSlot, type C02OcrSnapshot, type C02Review } from '../api/taskApi'

type Outcome = 'PROCEED' | 'REQUIREMENT_FAILED' | 'DOCUMENT_INSUFFICIENT'
type CaseData = {
  caseView?: { caseId: number; workItemId: number; revision: number; caseStatus: string; reviewStatus: string | null }
  requiredDocuments?: C02DocumentSlot[]
  documents?: C02Document[]
  ocr?: C02OcrSnapshot | null
  ocrError?: string | null
  precheck?: C02Review
  finalDecision?: { revision: number; outcome: string; customerMessage: string } | null
}
const requestId = (action: string, caseId: number) => `c02-staff-${action}-${caseId}-${crypto.randomUUID()}`

function OcrFieldSummary({ extracted }: { extracted: Record<string, unknown> }) {
  const rawFields = extracted.fields
  const fields = Array.isArray(rawFields)
    ? rawFields.filter((field): field is Record<string, unknown> => typeof field === 'object' && field !== null && !Array.isArray(field))
    : []
  const read = (name: string) => {
    const field = fields.find(candidate => candidate.name === name)
    const value = field?.value
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
    const record = value as Record<string, unknown>
    return {
      text: typeof record.text === 'string' && record.text.trim() ? record.text : null,
      confidence: typeof record.confidence === 'number' ? record.confidence : null,
    }
  }
  const candidates = [
    { key: 'grantor_names', label: '위임인 성명' },
    { key: 'agent_name', label: '대리인 성명' },
  ]
  return <>
    <p>위임장 OCR은 참고 자료입니다. 성명 대조·서명 진위·대리권을 자동 확인하지 않으며 원본 확인이 필요합니다.</p>
    <dl>{candidates.map(candidate => {
      const value = read(candidate.key)
      return <div key={candidate.key}>
        <dt>{candidate.label} ({candidate.key})</dt>
        <dd>{value?.text ?? '추출되지 않음 — 원본에서 직접 확인'}{value?.confidence !== null && value?.confidence !== undefined ? ` · 인식 신뢰도 ${(value.confidence * 100).toFixed(1)}%` : ''}</dd>
      </div>
    })}</dl>
    {fields.length === 0 && <p>필드 계약이 확인되지 않은 OCR 결과입니다. 추출값을 업무 판정에 사용하지 마세요.</p>}
    {fields.length > 0 && <>
      <h4>수신된 OCR 필드</h4>
      <ul>{fields.map((field, index) => {
        const name = typeof field.name === 'string' && field.name.trim() ? field.name : `이름 없는 필드 ${index + 1}`
        const value = typeof field.value === 'object' && field.value !== null && !Array.isArray(field.value)
          ? field.value as Record<string, unknown>
          : {}
        return <li key={`${name}-${index}`}>
          {name}: {typeof value.text === 'string' && value.text.trim() ? value.text : '읽을 수 있는 텍스트 없음'}
          {typeof value.confidence === 'number' ? ` · 신뢰도 ${(value.confidence * 100).toFixed(1)}%` : ''}
        </li>
      })}</ul>
    </>}
    <details>
      <summary>원본 OCR JSON 참고</summary>
      <pre>{JSON.stringify(extracted, null, 2)}</pre>
    </details>
  </>
}

/** Agent findings are reference material; only the authenticated employee can submit a final decision. */
export function C02StaffReviewPage() {
  const { caseId } = useParams()
  const parsedId = Number(caseId)
  const [data, setData] = useState<CaseData | null>(null)
  const [outcome, setOutcome] = useState<Outcome>('DOCUMENT_INSUFFICIENT')
  const [message, setMessage] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [decisionNotice, setDecisionNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = async () => {
    if (!Number.isSafeInteger(parsedId) || parsedId <= 0) { setError('Case ID가 올바르지 않습니다.'); return }
    setBusy(true); setError(null)
    try {
      const response = await c02Api.staffCase(parsedId, requestId('case', parsedId))
      const next = (response.data ?? null) as CaseData | null
      if (next?.caseView) {
        try {
          const ocr = await c02Api.staffOcr(parsedId, next.caseView.revision, requestId('ocr', parsedId))
          next.ocr = ocr.data ?? null
          next.ocrError = null
        } catch (cause) {
          next.ocr = null
          next.ocrError = cause instanceof Error ? cause.message : 'OCR 상태를 불러오지 못했습니다.'
        }
      }
      setData(next)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '직원 Case 조회에 실패했습니다.') }
    finally { setBusy(false) }
  }

  const precheck = async () => {
    const current = data?.caseView
    if (!current || !Number.isSafeInteger(parsedId)) { setError('최신 Case를 먼저 조회해 주세요.'); return }
    setBusy(true); setError(null)
    try {
      await c02Api.staffPrecheck(parsedId, { revision: current.revision, requestId: requestId('precheck', parsedId) })
      await load()
    } catch (cause) { setError(cause instanceof Error ? cause.message : '직원 Agent 사전검토에 실패했습니다.') }
    finally { setBusy(false) }
  }

  const decide = async () => {
    const current = data?.caseView
    const checked = data?.precheck
    if (!current || !checked || checked.revision !== current.revision || !Number.isSafeInteger(parsedId)) {
      setError('현재 Case revision의 사전검토 결과를 먼저 확인해 주세요.')
      return
    }
    setBusy(true); setError(null)
    try {
      const response = await c02Api.staffDecision(parsedId, { revision: current.revision, requestId: requestId('decision', parsedId), outcome, customerMessage: message })
      setDecisionNotice(response.data?.notification
        ? `직원 결정과 고객 알림이 저장됐습니다. 알림 ID ${response.data.notification.notificationId}. 외부 문자 발송 여부: ${response.data.notification.externalDeliveryAttempted ? '시도됨' : '시도 안 함'}.`
        : '직원 결정 응답에 알림 정보가 없습니다.')
      await load()
    } catch (cause) { setError(cause instanceof Error ? cause.message : '최종결정을 저장할 수 없습니다.') }
    finally { setBusy(false) }
  }

  const currentRevisionPrecheck = Boolean(data?.caseView && data.precheck?.revision === data.caseView.revision)
  const isSubmitted = data?.caseView?.caseStatus === 'SUBMITTED'

  return <section>
    <h1>C-02 직원 검토</h1>
    <p>직원 세션의 JWT 권한으로 접근합니다. Agent 사전검토와 OCR은 참고 자료이며, 최종 접수 검토는 직원이 직접 결정합니다.</p>
    {!taskBffEnabled && <p role="status">로그인 BFF 연결이 비활성화되어 있습니다. 내부 gateway token은 화면에서 사용하지 않습니다.</p>}
    <button type="button" disabled={!taskBffEnabled || busy} onClick={() => void load()}>{busy ? '처리 중…' : '최신 Case 조회'}</button>
    {error && <p role="alert">{error}</p>}{decisionNotice && <p role="status">{decisionNotice}</p>}
    {data?.caseView && <>
      <h2>Case {data.caseView.caseId}</h2>
      <p>WorkItem {data.caseView.workItemId} · 상태 {data.caseView.caseStatus} · revision {data.caseView.revision} · AI 검토 상태 {data.caseView.reviewStatus ?? '미실행'}</p>
      <h3>문서 manifest / 업로드 파일</h3>
      <ul>{(data.requiredDocuments ?? []).map(slot => <li key={`${slot.documentTypeCode}:${slot.personRef ?? 'COMMON'}`}>
        {slot.documentTypeCode} ({slot.requirement}){slot.personRef ? ` · 상속인 ${slot.personRef}` : ''} — {slot.note}
        <ul>{(data.documents ?? []).filter(document => document.documentSlotKey === `${slot.documentTypeCode}:${slot.personRef ?? 'COMMON'}`).map(document => <li key={document.documentFileId}>{document.fileName} · revision {document.caseRevision} · {document.status}</li>)}</ul>
      </li>)}</ul>
      <section aria-label="OCR reference">
        <h3>OCR 참고 결과</h3>
        <p>Case revision {data.ocr?.revision ?? data.caseView.revision}; OCR은 참고 자료이며 모든 문서는 직원이 원본과 대조해야 합니다. 자동 승인이나 권리 판단에 사용하지 않습니다.</p>
        {data.ocrError && <p role="alert">OCR 조회 실패: {data.ocrError}</p>}
        {(data.ocr?.documents ?? []).length === 0 && !data.ocrError && <p>현재 revision에 연결된 OCR 결과가 없습니다. 원본을 직접 확인해 주세요.</p>}
        <ul>{(data.ocr?.documents ?? []).map(item => <li key={item.documentFileId}>
          <strong>{item.documentTypeCode}{item.personRef ? ` · ${item.personRef}` : ''}</strong> — {item.method} / {item.status}; 문서 revision {item.caseRevision}
          <p>원본 확인 필요: {item.manualVerificationRequired ? '예' : '아니요'}{item.failureReason ? ` · 처리 사유: ${item.failureReason}` : ''}</p>
          {item.extracted && <OcrFieldSummary extracted={item.extracted} />}
        </li>)}</ul>
      </section>
      <button type="button" disabled={!taskBffEnabled || busy || !isSubmitted || Boolean(data.finalDecision) || currentRevisionPrecheck} onClick={() => void precheck()}>현재 revision 사전검토 실행</button>
      {data.precheck && <section>
        <h3>직원 Agent 사전검토 revision {data.precheck.revision}</h3>
        <p>실행 {data.precheck.agentRunStatus} · 검토 상태 {data.precheck.reviewStatus ?? '미지정'} · 최종 직원결정 아님</p>
        {data.precheck.error && <p role="alert">Agent 오류: {JSON.stringify(data.precheck.error)}</p>}
        <ul>{data.precheck.reviewItems?.map((item, index) => <li key={`${item.category ?? 'item'}-${index}`}>{item.category}: {item.message} ({item.source})</li>)}</ul>
      </section>}
      {data.finalDecision && <p role="status">직원 최종결정 저장됨: {data.finalDecision.outcome} · revision {data.finalDecision.revision}</p>}
      <h3>직원 최종결정</h3>
      <label>결정 <select value={outcome} onChange={event => setOutcome(event.target.value as Outcome)}><option value="PROCEED">진행 가능</option><option value="REQUIREMENT_FAILED">요건 미충족</option><option value="DOCUMENT_INSUFFICIENT">서류 보완 필요</option></select></label>
      <label>고객 안내 <textarea value={message} onChange={event => setMessage(event.target.value)} /></label>
      <button type="button" disabled={!taskBffEnabled || busy || !isSubmitted || !currentRevisionPrecheck || Boolean(data.finalDecision)} onClick={() => void decide()}>직원 최종결정 및 고객 알림 저장</button>
    </>}
  </section>
}
