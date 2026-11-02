import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { Link } from 'react-router-dom'
import type { B01Adapter, B01Eligibility, B01Loan, B01Result } from '../../api/b01'
import { toUserMessage } from '../../api/errorMessages'
import { SiteFooter } from '../../components/SiteFooter'
import { SiteHeader } from '../../components/SiteHeader'
import { SignFlow } from '../sign/SignFlow'
import { getSignSetup } from '../sign/signFixtures'
import {
  B01_STEPS, NOTICE_BASIS, NOTICE_ITEMS, NOTICE_TITLE, ORDER_DESCRIPTIONS, ORDER_LABELS, PENDING_SUBTITLE, PENDING_TITLE,
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

export function B01Flow({ adapter, workItemId, signerName }: { adapter: B01Adapter; workItemId: string; signerName: string }) {
  const [phase, setPhase] = useState<Phase>('select')

  const [loans, setLoans] = useState<B01Loan[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [eligibility, setEligibility] = useState<B01Eligibility | null>(null)
  const [checking, setChecking] = useState(false)
  const [checkError, setCheckError] = useState<string | null>(null)
  const checkSeq = useRef(0)

  const [agreed, setAgreed] = useState(false)
  const [upload, setUpload] = useState<Upload>({ state: 'idle' })
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [consenting, setConsenting] = useState(false)
  const [consentError, setConsentError] = useState<string | null>(null)

  const [result, setResult] = useState<B01Result | null>(null)
  const [finalizeError, setFinalizeError] = useState<string | null>(null)

  const headingRef = useRef<HTMLHeadingElement>(null)
  const focusKey = `${phase}:${finalizeError !== null}`
  const lastFocusKey = useRef(focusKey)

  const selected = loans?.find(loan => loan.loanId === selectedId) ?? null
  const requestedOrder = selected ? oppositeOrder(selected.repaymentOrder) : null

  const loadLoans = () => {
    setLoans(null)
    setLoadError(null)
    adapter.listLoans().then(setLoans).catch(error => setLoadError(toUserMessage(error)))
  }

  useEffect(() => {
    let active = true
    adapter.listLoans()
      .then(list => { if (active) setLoans(list) })
      .catch(error => { if (active) setLoadError(toUserMessage(error)) })
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
    const seq = ++checkSeq.current
    setSelectedId(loan.loanId)
    setEligibility(null)
    setCheckError(null)
    setChecking(true)
    adapter.checkEligibility(loan.loanId, oppositeOrder(loan.repaymentOrder))
      .then(next => { if (checkSeq.current === seq) setEligibility(next) })
      .catch(error => { if (checkSeq.current === seq) setCheckError(toUserMessage(error)) })
      .finally(() => { if (checkSeq.current === seq) setChecking(false) })
  }

  const onFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const input = event.currentTarget
    const file = input.files?.[0]
    if (!file) return
    setUpload({ state: 'uploading' })
    setUploadError(null)
    try {
      await adapter.uploadApplication(file)
      setUpload({ state: 'done', fileName: file.name })
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
      await adapter.recordConsent({ loanId: selected.loanId, requestedOrder })
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
      return <SignFlow {...setup} docs={docs} adapter={adapter.sign} backHref={`/tasks/b01/${workItemId}`} onDone={() => void runFinalize()} />
    }
  }

  const stepIndex = phase === 'select' ? 0 : phase === 'done' ? 2 : 1
  const uploadReady = !adapter.requiresApplicationDocument || upload.state === 'done'
  const canNext = phase === 'select'
    ? !!eligibility?.eligible && !checking
    : agreed && uploadReady && !consenting && upload.state !== 'uploading'
  const hint = phase === 'select'
    ? (!selected ? '변경할 대출을 선택해 주세요.'
      : checking ? '변경 가능 여부를 확인하고 있어요.'
        : eligibility?.eligible ? '선택한 대출은 충당순서를 바꿀 수 있어요.'
          : eligibility ? '변경할 수 없는 대출이에요. 다른 대출을 선택해 주세요.'
            : '변경 가능 여부를 확인하지 못했어요.')
    : (!agreed ? '유의사항 확인에 체크해야 변경할 수 있어요.'
      : !uploadReady ? '신청서를 첨부해야 변경할 수 있어요.'
        : '신청서에 서명하고 문자 인증을 마치면 조건을 다시 확인한 뒤 바로 변경돼요.')

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
              <input type="radio" name="b01-loan" checked={isSelected} onChange={() => pickLoan(loan)} className="b01-loan__radio" />
              <span className="b01-loan__body">
                <span className="b01-loan__head"><strong className="b01-loan__name">{loan.loanName}</strong><span className="b01-loan__no">{loan.loanNoMasked}</span></span>
                <span className="b01-loan__facts">
                  <span className="b01-fact"><span className="b01-fact__key">대출 잔액</span><strong>{formatWon(loan.balance)}</strong></span>
                  <span className="b01-fact"><span className="b01-fact__key">연체</span><strong>{loan.overdueDays > 0 ? `${loan.overdueDays}일` : '없음'}</strong></span>
                  <span className="b01-fact"><span className="b01-fact__key">기한의 이익</span><strong>{accelerationLabel(loan)}</strong></span>
                  <span className="b01-fact"><span className="b01-fact__key">현재 충당순서</span><strong>{ORDER_LABELS[loan.repaymentOrder]}</strong></span>
                </span>
                {isSelected && <span aria-live="polite" className="b01-loan__ruling">
                  {checking && <span className="b01-status">변경 가능 여부를 확인하고 있어요…</span>}
                  {checkError && <span role="alert" className="b01-alert">{checkError}</span>}
                  {eligibility && <>
                    <span className="b01-loan__result"><span className={`b01-result${eligibility.eligible ? ' b01-result--ok' : ' b01-result--no'}`}>{eligibility.eligible ? '변경 가능' : '변경 불가'}</span><span className="b01-loan__reason">{RULE_REASONS[eligibility.ruleCode]}</span></span>
                    {eligibility.thirdPartyProvider && <span className="b01-loan__notice"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="b01-loan__notice-icon"><path d="M12 3l10 18H2z"></path><path d="M12 10v4M12 17h.01"></path></svg>{THIRD_PARTY_NOTICE}</span>}
                    <span className="b01-basis">근거: {RULE_BASIS}</span>
                  </>}
                </span>}
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
            <h2 id="b01-done-title" ref={headingRef} tabIndex={-1} className="b01-done__title">{result.status === 'DONE' ? '충당순서 변경이 완료되었어요' : PENDING_TITLE}</h2>
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
          ? <button type="button" onClick={() => setPhase('confirm')} disabled={!canNext} className="b01-button b01-button--primary b01-bar__button">다음</button>
          : <button type="button" onClick={() => void consent()} disabled={!canNext} className="b01-button b01-button--primary b01-bar__button">{consenting ? '동의 기록 중…' : '서명·인증하고 변경하기'}</button>}
      </div>}
    </main>
    <SiteFooter />
  </div>
}
