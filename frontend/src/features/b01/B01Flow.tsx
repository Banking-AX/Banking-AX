import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { Link } from 'react-router-dom'
import type { B01Adapter, B01Context, B01Document, B01Eligibility, B01ImageMismatchCode, B01Loan, B01Result, B01VisualChecks, RepaymentOrder } from '../../api/b01'
import { ApiError } from '../../api/client'
import { toUserMessage } from '../../api/errorMessages'
import { SiteFooter } from '../../components/SiteFooter'
import { SiteHeader } from '../../components/SiteHeader'
import { SignFlow } from '../sign/SignFlow'
import { getSignSetup } from '../sign/signFixtures'
import {
  B01_STEPS, NOTICE_BASIS, NOTICE_ITEMS, NOTICE_TITLE, ORDER_DESCRIPTIONS, ORDER_LABELS, PENDING_SUBTITLE,
  RULE_BASIS, RULE_REASONS, THIRD_PARTY_NOTICE, oppositeOrder,
} from './b01Content'

type Phase = 'select' | 'confirm' | 'sign' | 'working' | 'done'
type Upload = { state: 'idle' } | { state: 'uploading' } | { state: 'done'; fileName: string }

const pad2 = (value: number) => String(value).padStart(2, '0')
function formatStamp(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return `${date.getFullYear()}.${pad2(date.getMonth() + 1)}.${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}
function formatWon(value: number | string): string {
  const amount = typeof value === 'string' && value.trim() === '' ? NaN : Number(value)
  return Number.isFinite(amount) ? `${amount.toLocaleString('ko-KR')}원` : '-'
}
function accelerationLabel(loan: B01Loan): string {
  if (!loan.accelerationDeclared) return '유지'
  return loan.accelerationDate ? `상실 (${loan.accelerationDate.replaceAll('-', '.')})` : '상실'
}

const IMAGE_MESSAGES: Record<B01ImageMismatchCode, string> = {
  IMAGE_ANALYSIS_NOT_AVAILABLE: '이미지 분석 결과가 없습니다. 신청서를 다시 제출해 주세요.',
  IMAGE_ANALYSIS_UNAVAILABLE: '이미지 분석 서비스를 사용할 수 없습니다. 잠시 후 다시 제출해 주세요.',
  ALIGNMENT_FAILED: '신청서 양식을 바르게 맞추지 못했습니다. 선명한 원본을 다시 제출해 주세요.',
  IMAGE_ANALYSIS_UNCERTAIN: '체크 또는 서명란 표시를 확실하게 판정하지 못했습니다. 신청서를 다시 제출해 주세요.',
  CHECKBOX_NOT_SELECTED: '변경할 충당순서 체크박스가 선택되지 않았습니다.',
  MULTIPLE_CHECKBOXES: '변경할 충당순서 체크박스가 두 개 모두 선택되었습니다.',
  REQUESTED_ORDER_MISMATCH: '신청서의 체크 결과가 화면에서 요청한 충당순서와 다릅니다.',
  DEBTOR_SIGNATURE_TRACE_NOT_FOUND: '신청서의 채무자 서명란에서 필기 흔적을 확인하지 못했습니다.',
}

function ocrTitle(method: string): string {
  if (method === 'MOCK') return 'Mock OCR 처리 결과'
  if (method === 'CLOVA_TEMPLATE') return 'CLOVA OCR 처리 결과'
  return '문서 텍스트 인식 결과'
}

function matchLabel(matched: boolean): string {
  return matched ? '일치' : '불일치 또는 확인 불가'
}

function checkboxLabel(checks: B01VisualChecks | null): string {
  if (!checks) return '분석 결과 없음'
  if (checks.uncertain) return '판정 불확실'
  if (checks.firstBox === 'CHECKED' && checks.secondBox === 'UNCHECKED') return '비용 → 원금 → 이자 선택'
  if (checks.firstBox === 'UNCHECKED' && checks.secondBox === 'CHECKED') return '비용 → 이자 → 원금 선택'
  if (checks.firstBox === 'CHECKED' && checks.secondBox === 'CHECKED') return '두 항목 모두 선택됨'
  if (checks.firstBox === 'UNCHECKED' && checks.secondBox === 'UNCHECKED') return '선택 없음'
  return '판정 불확실'
}

export function B01Flow({ adapter, workItemId, signerName }: { adapter: B01Adapter; workItemId: string; signerName: string }) {
  const [phase, setPhase] = useState<Phase>('select')
  const [context, setContext] = useState<B01Context | null>(null)
  const [restoring, setRestoring] = useState(true)
  const [resuming, setResuming] = useState(false)
  const [resumeError, setResumeError] = useState<string | null>(null)
  const resumeInFlight = useRef(false)

  const [loans, setLoans] = useState<B01Loan[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [requestedOrder, setRequestedOrder] = useState<RepaymentOrder | null>(null)
  const [eligibility, setEligibility] = useState<B01Eligibility | null>(null)
  const [checking, setChecking] = useState(false)
  const [checkError, setCheckError] = useState<string | null>(null)

  const [agreed, setAgreed] = useState(false)
  const [upload, setUpload] = useState<Upload>({ state: 'idle' })
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [document, setDocument] = useState<B01Document | null>(null)
  const [consenting, setConsenting] = useState(false)
  const [consentError, setConsentError] = useState<string | null>(null)

  const [result, setResult] = useState<B01Result | null>(null)
  const [finalizeError, setFinalizeError] = useState<string | null>(null)

  const headingRef = useRef<HTMLHeadingElement>(null)
  const focusKey = `${phase}:${finalizeError !== null}`
  const lastFocusKey = useRef(focusKey)

  const selected = loans?.find(loan => loan.loanId === selectedId) ?? null
  const onHold = context?.workItemStatus === 'ON_HOLD'

  const applyContext = (next: B01Context) => {
    setContext(next)
    setSelectedId(next.selectedLoanId)
    setRequestedOrder(next.requestedRepaymentOrder)
    setDocument(next.applicationDocument)
    setEligibility(next.lastEligibility)
    setAgreed(next.consentAgreed)
    setUpload(next.applicationDocument ? { state: 'done', fileName: next.applicationDocument.displayName } : { state: 'idle' })
    if (next.readyToExecute) { setResult({ status: 'PENDING' }); setPhase('done') }
    else if (next.consentAgreed || next.currentStep === 'MOCK_SIGNATURE' || next.currentStep === 'SMS_VERIFICATION') setPhase('sign')
    else if (next.selectedLoanId) setPhase('confirm')
    else setPhase('select')
  }

  const loadLoans = () => {
    setLoans(null)
    setLoadError(null)
    adapter.listLoans().then(setLoans).catch(error => setLoadError(toUserMessage(error)))
  }

  const resume = async () => {
    if (!context || context.workItemStatus !== 'ON_HOLD' || resumeInFlight.current) return
    resumeInFlight.current = true
    setResuming(true)
    setResumeError(null)
    try {
      const resumed = await adapter.resume(context.workItemVersion)
      if (resumed.status !== 'ACTIVE') throw new ApiError('INVALID_STATE', 409)
      const [saved, availableLoans] = await Promise.all([adapter.getContext(), adapter.listLoans()])
      applyContext(saved)
      setLoans(availableLoans)
      return
    } catch (error) {
      // The resume transaction may have committed even if its response was lost. Always reconcile first.
      try {
        const latest = await adapter.getContext()
        applyContext(latest)
        if (latest.workItemStatus === 'ACTIVE') {
          setLoans(await adapter.listLoans())
          return
        }
      } catch {
        // Keep the already restored screen state when reconciliation is unavailable.
      }
      if (error instanceof ApiError) {
        if (error.code === 'VERSION_CONFLICT') setResumeError('업무 정보가 변경되어 최신 상태를 불러왔어요. 내용을 확인한 뒤 다시 시도해 주세요.')
        else if (error.code === 'INVALID_STATE') setResumeError('업무 상태가 변경됐어요. 최신 상태를 확인한 뒤 다시 시도해 주세요.')
        else if (error.code === 'WORK_ITEM_NOT_AVAILABLE') setResumeError('이 업무를 재개할 수 없거나 접근할 수 없어요.')
        else if (error.code === 'WORKFLOW_ACTIVE_EXISTS') setResumeError('같은 업무 흐름에서 다른 업무가 진행 중이에요. 진행 중인 업무를 먼저 확인해 주세요.')
        else if (error.code === 'NETWORK_ERROR') setResumeError('재개 결과를 확인하지 못했어요. 서버 상태를 다시 확인한 뒤 안전하게 재시도해 주세요.')
        else setResumeError(toUserMessage(error))
      } else setResumeError(toUserMessage(error))
    } finally {
      resumeInFlight.current = false
      setResuming(false)
    }
  }

  useEffect(() => {
    let active = true
    setRestoring(true)
    adapter.getContext()
      .then(async saved => {
        if (!active) return
        applyContext(saved)
        if (saved.workItemStatus === 'ACTIVE') setLoans(await adapter.listLoans())
        else setLoans([])
      })
      .catch(error => { if (active) setLoadError(toUserMessage(error)) })
      .finally(() => { if (active) setRestoring(false) })
    return () => { active = false }
  }, [adapter])

  // Move focus to the new stage heading whenever the visible stage changes (not on first render).
  useEffect(() => {
    if (lastFocusKey.current === focusKey) return
    lastFocusKey.current = focusKey
    headingRef.current?.focus()
  }, [focusKey])

  const pickLoan = (loan: B01Loan) => {
    if (loan.loanId === selectedId) return
    setSelectedId(loan.loanId)
    setRequestedOrder(oppositeOrder(loan.repaymentOrder))
    setEligibility(null)
    setDocument(null)
    setUpload({ state: 'idle' })
    setAgreed(false)
    setCheckError(null)
  }

  const saveSelection = async () => {
    if (!selected || !requestedOrder || checking) return
    setChecking(true); setCheckError(null)
    try { applyContext(await adapter.saveSelection(selected.loanId, requestedOrder)); setPhase('confirm') }
    catch (error) { setCheckError(toUserMessage(error)) }
    finally { setChecking(false) }
  }

  const onFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const input = event.currentTarget
    const file = input.files?.[0]
    if (!file) return
    setUpload({ state: 'uploading' })
    setUploadError(null)
    setCheckError(null)
    setDocument(null)
    setEligibility(null)
    setAgreed(false)
    try {
      const uploaded = await adapter.uploadApplication(file)
      setDocument({ documentFileId: uploaded.documentFileId, version: uploaded.version, displayName: file.name,
        sizeBytes: file.size, contentType: file.type, uploadedAt: new Date().toISOString(),
        ocr: { method: uploaded.method, status: uploaded.status, loans: uploaded.fields.loans,
          requestedRepaymentOrder: uploaded.fields.requestedRepaymentOrder,
          visualChecks: uploaded.visualChecks,
          authenticityVerified: uploaded.authenticityVerified, manualVerificationRequired: uploaded.manualVerificationRequired } })
      setUpload({ state: 'done', fileName: file.name })
      if (selected && requestedOrder) {
        setChecking(true)
        try { setEligibility(await adapter.checkEligibility(selected.loanId, requestedOrder)) }
        catch (error) { setCheckError(toUserMessage(error)) }
        finally { setChecking(false) }
      }
    } catch (error) {
      setUpload({ state: 'idle' })
      setUploadError(toUserMessage(error))
      input.value = ''
    }
  }

  const consent = async () => {
    if (!selected || !requestedOrder) return
    setConsenting(true)
    setConsentError(null)
    try {
      // The consent time is not kept client-side; the adapter returns it from finalize.
      if (!document) return
      await adapter.recordConsent({ loanId: selected.loanId, requestedOrder, documentFileId: document.documentFileId })
      setPhase('sign')
    } catch (error) {
      setConsentError(toUserMessage(error))
    } finally {
      setConsenting(false)
    }
  }

  const runFinalize = async () => {
    setPhase('working')
    setFinalizeError(null)
    try {
      setResult(await adapter.finalize())
      setPhase('done')
    } catch (error) {
      setFinalizeError(toUserMessage(error))
    }
  }

  if (restoring) return <div className="b01-page"><SiteHeader /><main className="b01-main"><p role="status" className="b01-status">저장된 진행 상태를 불러오고 있어요.</p></main><SiteFooter /></div>

  if (onHold) return <div className="b01-page">
    <SiteHeader />
    <main className="b01-main">
      <section className="b01-card">
        <h1 className="b01-card__title">보류된 채무변제 충당순서 변경 업무</h1>
        <div role="status" className="b01-alert">저장된 내용은 유지되고 있어요. 이어서 진행하면 마지막으로 저장된 단계부터 계속할 수 있어요.</div>
        <dl className="b01-dl">
          <dt className="b01-dl__key">선택한 대출</dt><dd className="b01-dl__value">{selectedId ? `대출 ID ${selectedId}` : '선택 전'}</dd>
          <dt className="b01-dl__key">요청 충당순서</dt><dd className="b01-dl__value">{requestedOrder ? ORDER_LABELS[requestedOrder] : '선택 전'}</dd>
          <dt className="b01-dl__key">신청서</dt><dd className="b01-dl__value">{document?.displayName ?? '업로드 전'}</dd>
          <dt className="b01-dl__key">동의·서명·SMS</dt><dd className="b01-dl__value">{context?.consentAgreed ? '동의 완료' : '동의 전'} · {context?.mockSignatureCompleted ? '모의서명 완료' : '모의서명 전'} · {context?.smsVerified ? 'SMS 완료' : 'SMS 전'}</dd>
        </dl>
        {resumeError && <div role="alert" className="b01-alert">{resumeError}</div>}
        <div className="b01-done__actions">
          <button type="button" onClick={() => void resume()} disabled={resuming}
            className="b01-button b01-button--primary">{resuming ? '재개 상태 확인 중…' : '이어서 진행'}</button>
          <Link to="/chat" className="b01-button b01-button--plain">채팅으로 돌아가기</Link>
        </div>
      </section>
    </main>
    <SiteFooter />
  </div>

  if (phase === 'sign' && selected && requestedOrder) {
    const setup = getSignSetup('b01', signerName)
    if (setup) {
      // Replace the design's sample loan values with the selected loan.
      const docs = setup.docs.map(doc => ({
        ...doc,
        fields: doc.fields.map(field => {
          if (field.k === '대상 대출') return { ...field, v: `${selected.loanName} ${selected.loanNoMasked}` }
          if (field.k === '변경 전') return { ...field, v: ORDER_LABELS[selected.repaymentOrder] }
          if (field.k === '변경 후') return { ...field, v: ORDER_LABELS[requestedOrder] }
          if (field.k === '유의사항 확인') return { ...field, v: `${NOTICE_ITEMS.length}개 항목` }
          return field
        }),
      }))
      return <SignFlow {...setup} docs={docs} adapter={adapter.sign} backHref={`/tasks/b01/${workItemId}`}
        allowDemoSignature={false} initialStep={context?.mockSignatureCompleted ? 'otp' : 'doc'}
        onDone={() => void runFinalize()} doneLabel="본인확인 결과 보기" />
    }
  }

  const stepIndex = phase === 'select' ? 0 : phase === 'done' ? 2 : 1
  const uploadReady = !adapter.requiresApplicationDocument || upload.state === 'done'
  const canNext = phase === 'select'
    ? !!selected && !!requestedOrder && !checking && !onHold
    : agreed && uploadReady && !!eligibility?.eligible && !consenting && upload.state !== 'uploading' && !checking && !onHold
  const hint = phase === 'select'
    ? (!selected ? '변경할 대출을 선택해 주세요.' : checking ? '선택 정보를 저장하고 있어요.' : '신청서에 작성할 변경 내용을 확인해 주세요.')
    : (!agreed ? '유의사항 확인에 체크해야 변경할 수 있어요.'
      : !uploadReady ? '신청서를 첨부해야 변경할 수 있어요.'
        : !eligibility?.eligible ? '신청서와 대출정보 검증을 통과해야 다음 단계로 진행할 수 있어요.'
          : '서명과 문자 인증을 마치면 변경 처리 전 단계까지 준비돼요.')

  return <div className="b01-page">
    <SiteHeader />
    <main className="b01-main">
      <div className="b01-top">
        <div className="b01-top__titles">
          <div className="b01-tags"><span className="b01-tag b01-tag--auto">B · 자동처리</span><span className="b01-tag">비대면 가정 시나리오 · 실제로는 영업점 방문 업무</span></div>
          <h1 className="b01-title">채무변제 충당순서 변경</h1>
        </div>
        {phase !== 'done' && <Link to="/chat" className="b01-button b01-button--plain b01-top__back">채팅으로 돌아가기</Link>}
      </div>

      <ol aria-label="진행 단계" className="b01-steps">
        {B01_STEPS.map((name, index) => {
          const done = index < stepIndex || phase === 'done'
          const current = index === stepIndex
          return <li key={name} aria-current={current ? 'step' : undefined} className="b01-step">
            <span className={`b01-step__dot${done ? ' b01-step__dot--done' : current ? ' b01-step__dot--current' : ''}`}>{done ? '✓' : index + 1}</span>
            <span className={`b01-step__label${current ? ' b01-step__label--current' : ''}`}>{name}</span>
          </li>
        })}
      </ol>

      {phase === 'select' && <section aria-labelledby="b01-select-title" className="b01-card">
        <div className="b01-card__head">
          <h2 id="b01-select-title" ref={headingRef} tabIndex={-1} className="b01-card__title">변경할 대출을 선택해 주세요</h2>
          <span className="b01-card__sub">대출을 고르면 변경 가능 여부를 규칙으로 확인해요.</span>
        </div>
        {!loans && !loadError && <p role="status" className="b01-status">대출 목록을 불러오고 있어요.</p>}
        {loadError && <div role="alert" className="b01-alert b01-alert--row">
          <span className="b01-alert__text">{loadError}</span>
          <button type="button" onClick={loadLoans} className="b01-alert__retry">다시 시도</button>
        </div>}
        {loans && loans.length === 0 && <p role="status" className="b01-status">변경할 수 있는 대출이 없어요.</p>}
        {loans && loans.length > 0 && <fieldset className="b01-loans">
          <legend className="b01-sr-only">대상 대출</legend>
          {loans.map(loan => {
            const isSelected = loan.loanId === selectedId
            return <label key={loan.loanId} className={`b01-loan${isSelected ? ' b01-loan--selected' : ''}`}>
              <input type="radio" name="b01-loan" checked={isSelected} onChange={() => pickLoan(loan)} disabled={onHold} className="b01-loan__radio" />
              <span className="b01-loan__body">
                <span className="b01-loan__head"><strong className="b01-loan__name">{loan.loanName}</strong><span className="b01-loan__no">{loan.loanNoMasked}</span></span>
                <span className="b01-loan__facts">
                  <span className="b01-fact"><span className="b01-fact__key">대출 잔액</span><strong>{formatWon(loan.balance)}</strong></span>
                  <span className="b01-fact"><span className="b01-fact__key">연체</span><strong>{loan.overdueDays > 0 ? `${loan.overdueDays}일` : '없음'}</strong></span>
                  <span className="b01-fact"><span className="b01-fact__key">기한의 이익</span><strong>{accelerationLabel(loan)}</strong></span>
                  <span className="b01-fact"><span className="b01-fact__key">현재 충당순서</span><strong>{ORDER_LABELS[loan.repaymentOrder]}</strong></span>
                </span>
                {isSelected && <span aria-live="polite" className="b01-loan__ruling"><span className="b01-status">선택됨 · 신청서 업로드 후 적합성을 확인해요.</span>{checkError && <span role="alert" className="b01-alert">{checkError}</span>}</span>}
              </span>
            </label>
          })}
        </fieldset>}
      </section>}

      {phase === 'confirm' && selected && requestedOrder && <section aria-labelledby="b01-confirm-title" className="b01-card b01-card--wide-gap">
        <div className="b01-card__head">
          <h2 id="b01-confirm-title" ref={headingRef} tabIndex={-1} className="b01-card__title">변경 내용을 확인하고 동의해 주세요</h2>
          <span className="b01-card__sub">{selected.loanName} {selected.loanNoMasked}</span>
        </div>
        <div className="b01-compare">
          <div className="b01-order"><span className="b01-order__label">현재 순서</span><strong className="b01-order__value">{ORDER_LABELS[selected.repaymentOrder]}</strong><span className="b01-order__desc">{ORDER_DESCRIPTIONS[selected.repaymentOrder]}</span></div>
          <div className="b01-compare__arrow"><svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#2E70C3" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" role="img" aria-label="변경"><path d="M5 12h14M13 6l6 6-6 6"></path></svg></div>
          <div className="b01-order b01-order--next"><span className="b01-order__label">변경 후 순서</span><strong className="b01-order__value">{ORDER_LABELS[requestedOrder]}</strong><span className="b01-order__desc">{ORDER_DESCRIPTIONS[requestedOrder]}</span></div>
        </div>
        <div className="b01-notice">
          <strong className="b01-notice__title">{NOTICE_TITLE}</strong>
          <ol className="b01-notice__list">
            {NOTICE_ITEMS.map((item, index) => <li key={index}>{item.text}{'strong' in item && <><strong>{item.strong}</strong>{item.after}</>}</li>)}
          </ol>
          <span className="b01-basis">근거: {NOTICE_BASIS}</span>
        </div>
        {adapter.requiresApplicationDocument && <div className="b01-upload">
          <strong className="b01-upload__title">신청서 첨부</strong>
          <span className="b01-upload__desc">작성한 충당순서 변경 신청서(B01-01)를 PDF, PNG, JPEG 파일로 올려 주세요.</span>
          <label className="b01-upload__picker">
            <span className="b01-button b01-button--outline">{upload.state === 'done' ? '다른 파일 선택' : '파일 선택'}</span>
            <input type="file" accept="application/pdf,image/png,image/jpeg,.pdf,.png,.jpg,.jpeg" onChange={onFile} disabled={upload.state === 'uploading' || consenting} className="b01-upload__input" />
          </label>
          <span aria-live="polite" className="b01-upload__status">
            {upload.state === 'uploading' && '올리는 중…'}
            {upload.state === 'done' && `신청서를 첨부했어요: ${upload.fileName}`}
          </span>
          {uploadError && <span role="alert" className="b01-alert">{uploadError}</span>}
        </div>}
        {document?.ocr && <div className="b01-notice">
          <strong className="b01-notice__title">{ocrTitle(document.ocr.method)}</strong>
          <span>처리 상태: {document.ocr.status} · 방식: {document.ocr.method}</span>
          <span>인식 계좌: {document.ocr.loans.map(item => item.maskedAccountNumber).join(', ') || '없음'}</span>
          <span>인식 잔액: {document.ocr.loans.length === 1 ? formatWon(document.ocr.loans[0].balance) : '확인 불가'}</span>
          <span>인식 기한의 이익 상실일: {document.ocr.loans.length === 1 ? (document.ocr.loans[0].accelerationDate ?? '확인 불가') : '확인 불가'}</span>
          <span>변경순서 체크박스: {checkboxLabel(document.ocr.visualChecks)}</span>
          <span>채무자 서명란 필기 흔적: {document.ocr.visualChecks?.debtorSignatureTrace === 'TRACE_PRESENT' ? '흔적 감지' : document.ocr.visualChecks?.debtorSignatureTrace === 'TRACE_ABSENT' ? '흔적 없음' : '판정 불확실'}</span>
          {document.ocr.method === 'MOCK'
            ? <span className="b01-basis">등록된 테스트 파일 해시와 Fixture를 연결한 Mock 결과입니다. 실제 문자 인식·문서 진위·본인 서명 검증 결과가 아닙니다.</span>
            : <span className="b01-basis">텍스트 인식과 이미지 흔적 분석 결과입니다. 서명자 본인 여부나 문서 진위를 확인한 결과가 아닙니다.</span>}
        </div>}
        {checking && <p role="status" className="b01-status">신청서와 대출정보를 비교하고 있어요.</p>}
        {checkError && <div role="alert" className="b01-alert">{checkError}</div>}
        {eligibility && <div className="b01-loan__ruling">
          <span className="b01-loan__result"><span className={`b01-result${eligibility.eligible ? ' b01-result--ok' : ' b01-result--no'}`}>{eligibility.eligible ? '서류 항목 검증 통과' : '서류 재제출 또는 확인 필요'}</span><span className="b01-loan__reason">{RULE_REASONS[eligibility.ruleCode]}</span></span>
          <span>계좌번호 인식 결과: {matchLabel(eligibility.ocrMatched && !eligibility.mismatchCodes.includes('ACCOUNT_NUMBER_MISMATCH'))}</span>
          <span>대출잔액 일치 여부: {matchLabel(eligibility.ocrMatched && !eligibility.mismatchCodes.includes('BALANCE_MISMATCH'))}</span>
          <span>기한의 이익 상실일 일치 여부: {matchLabel(eligibility.ocrMatched && !eligibility.mismatchCodes.includes('ACCELERATION_DATE_MISMATCH'))}</span>
          <span>변경순서 체크 결과와 요청 일치 여부: {matchLabel(eligibility.image.matched && !eligibility.image.mismatchCodes.includes('REQUESTED_ORDER_MISMATCH'))}</span>
          <span>채무자 서명란 필기 흔적: {eligibility.image.mismatchCodes.includes('DEBTOR_SIGNATURE_TRACE_NOT_FOUND') ? '흔적 없음' : eligibility.image.matched ? '흔적 감지' : '확인 불가'}</span>
          {!eligibility.ocrMatched && <span className="b01-loan__notice">신청서 정보가 선택한 대출과 일치하지 않아요: {eligibility.mismatchCodes.join(', ')}</span>}
          {!eligibility.image.matched && <span className="b01-loan__notice">{eligibility.image.mismatchCodes.map(code => IMAGE_MESSAGES[code]).join(' ')}</span>}
          {eligibility.thirdPartyProvider && <span className="b01-loan__notice">{THIRD_PARTY_NOTICE}</span>}
          {eligibility.manualVerificationRequired && <span className="b01-basis">서류 항목 검증과 최종 모의 처리 허용 여부는 별개입니다. 문서 진위와 서명자 본인 여부는 확인되지 않았습니다.</span>}
          <span className="b01-basis">근거: {RULE_BASIS}</span>
        </div>}
        <label className={`b01-agree${agreed ? ' b01-agree--on' : ''}`}><input type="checkbox" checked={agreed} disabled={consenting} onChange={() => setAgreed(value => !value)} className="b01-agree__input" />유의사항을 확인했습니다</label>
        <span className="b01-card__foot">동의하시면 서명과 문자 인증 단계로 넘어가요.</span>
        {consentError && <div role="alert" className="b01-alert">{consentError}</div>}
      </section>}

      {phase === 'working' && !finalizeError && <section role="status" className="b01-working">
        <span className="b01-working__spinner" aria-hidden="true"></span>
        <h2 ref={headingRef} tabIndex={-1} className="b01-working__title">변경 조건을 다시 확인하고 있어요</h2>
        <span className="b01-working__sub">최신 대출 상태 · 기한의 이익 상실 여부 · 현재 충당순서</span>
      </section>}

      {phase === 'working' && finalizeError && <section className="b01-failed">
        <h2 ref={headingRef} tabIndex={-1} className="b01-sr-only">처리 실패</h2>
        <div role="alert" className="b01-alert b01-alert--row">
          <span className="b01-alert__text">{finalizeError}</span>
          <button type="button" onClick={() => void runFinalize()} className="b01-alert__retry">다시 시도</button>
        </div>
      </section>}

      {phase === 'done' && result && selected && requestedOrder && <section aria-labelledby="b01-done-title" className={`b01-done${result.status === 'DONE' ? '' : ' b01-done--pending'}`}>
        <div className="b01-done__head">
          <span className="b01-done__icon">{result.status === 'DONE'
            ? <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12l5 5 9-10"></path></svg>
            : <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v5l3 2"></path></svg>}</span>
          <div className="b01-done__titles">
            <h2 id="b01-done-title" ref={headingRef} tabIndex={-1} className="b01-done__title">{result.status === 'DONE' ? '충당순서 변경이 완료되었어요' : '본인확인이 완료되었습니다. 충당순서 변경 처리 전 단계입니다.'}</h2>
            {result.status === 'DONE'
              ? result.maskedEmail && <span className="b01-done__sub">결과를 {result.maskedEmail}으로 보냈어요</span>
              : <span className="b01-done__sub">{PENDING_SUBTITLE}</span>}
          </div>
        </div>
        <div className="b01-done__body">
          <dl className="b01-dl">
            <dt className="b01-dl__key">대상 대출</dt><dd className="b01-dl__value">{selected.loanName} {selected.loanNoMasked}</dd>
            <dt className="b01-dl__key">변경 전</dt><dd className="b01-dl__value">{ORDER_LABELS[selected.repaymentOrder]}</dd>
            <dt className="b01-dl__key">변경 후</dt><dd className="b01-dl__value b01-dl__value--strong">{ORDER_LABELS[requestedOrder]}</dd>
            {result.consentAt && <><dt className="b01-dl__key">유의사항 확인</dt><dd className="b01-dl__value">{formatStamp(result.consentAt)}</dd></>}
            {result.status === 'DONE' && result.processedAt && <><dt className="b01-dl__key">처리 일시</dt><dd className="b01-dl__value">{formatStamp(result.processedAt)}</dd></>}
          </dl>
          {/* BDetail 화면 연결 때 복구 예정: "처리 내역은 대화가 삭제된 뒤에도 업무 요청 기록에서 볼 수 있어요." */}
          {result.status === 'DONE' && <p className="b01-done__note">연체금을 모두 갚는 등 기한의 이익이 다시 생기면 순서가 자동으로 원래대로 돌아가고, 처리 이력에 남아요.</p>}
          {/* ChatBReturn 연결 예정. The "업무 요청 기록에서 보기" button waits for BDetail. */}
          <div className="b01-done__actions"><Link to="/chat" className="b01-button b01-button--primary">채팅으로 돌아가기</Link></div>
        </div>
      </section>}

      {(phase === 'select' || phase === 'confirm') && <div className="b01-bar">
        <span aria-live="polite" className={`b01-bar__hint${canNext ? '' : ' b01-bar__hint--warn'}`}>{hint}</span>
        {phase === 'confirm' && <button type="button" onClick={() => setPhase('select')} disabled={consenting} className="b01-button b01-button--plain b01-bar__button">이전</button>}
        {phase === 'select'
          ? <button type="button" onClick={() => void saveSelection()} disabled={!canNext} className="b01-button b01-button--primary b01-bar__button">다음</button>
          : <button type="button" onClick={() => void consent()} disabled={!canNext} className="b01-button b01-button--primary b01-bar__button">{consenting ? '동의 기록 중…' : '서명·인증하고 변경하기'}</button>}
      </div>}
    </main>
    <SiteFooter />
  </div>
}
