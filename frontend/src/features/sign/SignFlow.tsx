import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { toUserMessage } from '../../api/errorMessages'
import { SMS_MAX_ATTEMPTS, SMS_RESEND_COOLDOWN_SEC, type CodeIssue, type CodeVerification, type SignAdapter } from '../../api/sign'
import { SiteFooter } from '../../components/SiteFooter'
import { SiteHeader } from '../../components/SiteHeader'
import { SignaturePad, countPoints, signaturePathToPngBlob, strokesToPath, type SignatureStroke } from './SignaturePad'

export type SignDocument = { name: string; ref: string; body: string; fields: { k: string; v: string; emphasis?: boolean }[] }

export type SignFlowProps = {
  adapter: SignAdapter
  docs: SignDocument[]
  taskName: string
  afterText: string
  note?: string
  signer: string
  backHref: string
  doneHref?: string
  /** When set, the final button calls this instead of linking to doneHref. */
  onDone?: () => void
  doneLabel: string
  storesSignature: boolean
  demoPath: string
  /** Keep sample signatures on design/demo routes, but require handwriting on real task routes. */
  allowDemoSignature?: boolean
  /** Resume an already persisted task at the signature or OTP step. */
  initialStep?: 'doc' | 'sign' | 'otp'
  /** Reports the visible step (and the server's masked phone once a code is issued), e.g. for the AI helper guide. */
  onStepChange?: (step: SignStep, maskedPhone: string | null) => void
}

export type SignStep = 'doc' | 'sign' | 'otp' | 'done'
type Step = SignStep
type OtpMessage = { tone: 'error' | 'info'; text: string } | null

const STEPS: { key: Step; label: string }[] = [
  { key: 'doc', label: '문서 확인' }, { key: 'sign', label: '손글씨 서명' }, { key: 'otp', label: '휴대폰 인증' }, { key: 'done', label: '서명 완료' },
]
const MIN_POINTS = 12
const DEMO_POINTS = 99
const EXPIRED_TEXT = '인증번호 입력 시간이 지났어요. 새 번호를 받아 주세요.'

export function completedSignaturePreview(path: string): string | null {
  return path || null
}

const pad2 = (value: number) => String(value).padStart(2, '0')
const formatClock = (sec: number) => `${pad2(Math.floor(sec / 60))}:${pad2(sec % 60)}`
const formatStamp = (date: Date) => `${date.getFullYear()}.${pad2(date.getMonth() + 1)}.${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`
function formatDuration(sec: number): string {
  const minutes = Math.floor(sec / 60)
  const seconds = sec % 60
  if (minutes && seconds) return `${minutes}분 ${seconds}초`
  return minutes ? `${minutes}분` : `${seconds}초`
}

export function SignFlow({ adapter, docs, taskName, afterText, note, signer, backHref, doneHref, onDone, doneLabel, storesSignature, demoPath, allowDemoSignature = true, initialStep = 'doc', onStepChange }: SignFlowProps) {
  const [step, setStep] = useState<Step>(initialStep)
  // This changes only what is displayed. The persisted execution step remains OTP.
  const [reviewingPrevious, setReviewingPrevious] = useState(false)

  const [read, setRead] = useState<boolean[]>(() => docs.map(() => false))
  const [cur, setCur] = useState(0)

  const [strokes, setStrokes] = useState<SignatureStroke[]>([])
  const [demo, setDemo] = useState(false)
  const [agree, setAgree] = useState(false)
  const [tooShort, setTooShort] = useState(false)
  const [savingSignature, setSavingSignature] = useState(false)
  const [signatureError, setSignatureError] = useState<string | null>(null)
  const [signatureLocked, setSignatureLocked] = useState(initialStep === 'otp')

  const [issue, setIssue] = useState<CodeIssue | null>(null)
  const [ttlSec, setTtlSec] = useState<number | null>(null)
  const [issuedAt, setIssuedAt] = useState<number | null>(null)
  const [issuing, setIssuing] = useState(false)
  const [code, setCode] = useState('')
  const [wrongCount, setWrongCount] = useState(0)
  const [codeLocked, setCodeLocked] = useState(false)
  const [verifying, setVerifying] = useState(false)
  const [otpMessage, setOtpMessage] = useState<OtpMessage>(null)
  const [now, setNow] = useState(() => Date.now())

  const [verification, setVerification] = useState<CodeVerification | null>(null)
  const [signedAt, setSignedAt] = useState<Date | null>(null)
  const [working, setWorking] = useState(false)
  const [completeFailed, setCompleteFailed] = useState(false)

  const headingRef = useRef<HTMLHeadingElement>(null)
  const focusKey = `${step}:${working}:${completeFailed}`
  const lastFocusKey = useRef(focusKey)

  const n = docs.length
  const readN = read.filter(Boolean).length
  const pointCount = demo ? DEMO_POINTS : countPoints(strokes)
  const path = demo ? demoPath : strokesToPath(strokes)
  const stepIndex = STEPS.findIndex(item => item.key === step)
  const remainingSec = issue ? Math.max(0, Math.ceil((Date.parse(issue.expiresAt) - now) / 1000)) : 0
  const expired = issue !== null && remainingSec === 0
  const resendLeft = issuedAt === null ? 0 : Math.max(0, Math.ceil((issuedAt + SMS_RESEND_COOLDOWN_SEC * 1000 - now) / 1000))
  const codeInvalid = otpMessage?.tone === 'error' && issue !== null

  useEffect(() => {
    if (!issue || verification) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [issue, verification])

  const maskedPhone = issue?.maskedPhone ?? null
  useEffect(() => { onStepChange?.(step, maskedPhone) }, [step, maskedPhone, onStepChange])

  // Move focus to the new step heading whenever the visible stage changes (not on first render).
  useEffect(() => {
    if (lastFocusKey.current === focusKey) return
    lastFocusKey.current = focusKey
    headingRef.current?.focus()
  }, [focusKey])

  const toggleRead = () => {
    const next = read.slice()
    next[cur] = !next[cur]
    setRead(next)
    if (next[cur]) {
      const following = next.findIndex((value, index) => index > cur && !value)
      const pending = following >= 0 ? following : next.findIndex(value => !value)
      if (pending >= 0) setCur(pending)
    }
  }

  const onStrokes = (next: SignatureStroke[]) => {
    setDemo(false)
    setStrokes(next)
    setTooShort(false)
    setSignatureError(null)
  }
  const clearSignature = () => { setStrokes([]); setDemo(false); setAgree(false); setTooShort(false); setSignatureError(null) }
  const demoSignature = () => { setStrokes([]); setDemo(true); setTooShort(false); setSignatureError(null) }

  const submitSignature = async () => {
    if (pointCount < MIN_POINTS) { setTooShort(true); return }
    setSavingSignature(true)
    setSignatureError(null)
    try {
      await adapter.submitSignature({ path, pointCount, documentCount: n, toPngBlob: () => signaturePathToPngBlob(path) })
      setSignatureLocked(true)
      setStep('otp')
    } catch (error) {
      setSignatureError(toUserMessage(error))
    } finally {
      setSavingSignature(false)
    }
  }

  const sendCode = async () => {
    setIssuing(true)
    try {
      const next = await adapter.issueCode()
      const sentAt = Date.now()
      setIssue(next)
      setTtlSec(Math.max(0, Math.round((Date.parse(next.expiresAt) - sentAt) / 1000)))
      setIssuedAt(sentAt)
      setNow(sentAt)
      setCode('')
      setWrongCount(0)
      setCodeLocked(false)
      setOtpMessage(null)
    } catch (error) {
      setOtpMessage({ tone: 'error', text: toUserMessage(error) })
    } finally {
      setIssuing(false)
    }
  }

  const runComplete = async (result: CodeVerification) => {
    setWorking(true)
    setCompleteFailed(false)
    try {
      await adapter.complete(result)
      setStep('done')
    } catch {
      setCompleteFailed(true)
    } finally {
      setWorking(false)
    }
  }

  const verify = async () => {
    if (!issue) return
    setVerifying(true)
    try {
      const result = await adapter.verifyCode(issue.challengeId, code)
      setVerification(result)
      // The server does not return a verification time, so this is the client clock.
      setSignedAt(new Date())
      setOtpMessage(null)
      void runComplete(result)
    } catch (error) {
      const errorCode = error instanceof ApiError ? error.code : ''
      if (errorCode === 'OTP_INVALID') {
        const next = wrongCount + 1
        setWrongCount(next)
        setOtpMessage({ tone: 'error', text: `인증번호가 맞지 않아요. 다시 입력해 주세요 (${Math.min(next, SMS_MAX_ATTEMPTS)}/${SMS_MAX_ATTEMPTS})` })
      } else if (errorCode === 'OTP_ATTEMPTS_EXCEEDED') {
        // Servers lock at different counts; trust the server regardless of the local count.
        setCodeLocked(true)
        setOtpMessage({ tone: 'error', text: `${SMS_MAX_ATTEMPTS}회 틀렸어요. 새 인증번호를 받아 주세요.` })
      } else {
        setOtpMessage({ tone: 'error', text: toUserMessage(error) })
      }
    } finally {
      setVerifying(false)
    }
  }

  const canNext = step === 'doc' ? readN === n
    : step === 'sign' ? pointCount > 0 && agree && !savingSignature
      : step === 'otp' ? issue !== null && code.length === 6 && !expired && !codeLocked && !verifying
        : false
  const next = () => {
    if (!canNext) return
    if (step === 'doc') setStep('sign')
    else if (step === 'sign') void submitSignature()
    else if (step === 'otp') void verify()
  }

  const hint = step === 'doc'
    ? (readN === n ? '모든 문서를 확인했어요.' : `문서 ${n - readN}건을 더 확인해야 서명할 수 있어요.`)
    : step === 'sign'
      ? (pointCount >= MIN_POINTS ? (agree ? '서명과 동의가 준비됐어요.' : '서명 동의에 체크해 주세요.') : '서명 칸에 서명해 주세요.')
      : '등록된 휴대폰으로 받은 인증번호를 입력해 주세요.'
  const nextLabel = step === 'otp' ? '인증하고 서명하기' : step === 'sign' && savingSignature ? '서명 저장 중…' : '다음'
  const showBar = step !== 'done' && verification === null && !reviewingPrevious

  const otpStatusText = otpMessage?.text
    ?? (expired ? EXPIRED_TEXT : issue && ttlSec !== null ? `${issue.maskedPhone}로 인증번호를 보냈어요. ${formatDuration(ttlSec)} 안에 입력해 주세요.` : '')
  const otpStatusError = otpMessage?.tone === 'error' || (!otpMessage && expired)

  const currentDoc = docs[cur]

  return <div className="sign-page">
    <SiteHeader />
    <main className="sign-main">
      <div className="sign-intro">
        <nav aria-label="현재 위치" className="sign-breadcrumb">
          <Link to={backHref} className="sign-breadcrumb__link">{taskName}</Link>
          <span aria-hidden="true">›</span>
          <span aria-current="page" className="sign-breadcrumb__current">서명·인증</span>
        </nav>
        <div className="sign-title-row">
          <h1 className="sign-title">{taskName} 서명·인증</h1>
        </div>
        <p className="sign-lead">서명할 문서를 확인하고, 손글씨 서명과 휴대폰 인증을 거쳐 서명해요. {afterText}.</p>
      </div>

      <ol aria-label="서명 단계" className="sign-steps">
        {STEPS.map((item, index) => {
          const isCurrent = index === stepIndex
          const done = index < stepIndex || step === 'done'
          return <li key={item.key} aria-current={isCurrent ? 'step' : undefined} className={`sign-step${isCurrent ? ' sign-step--current' : ''}`}>
            <span className={`sign-step__dot${done || isCurrent ? ' sign-step__dot--active' : ''}`}>{done && !isCurrent ? '✓' : index + 1}</span>
            <span className="sign-step__label">{item.label}</span>
          </li>
        })}
      </ol>

      {(step === 'doc' || reviewingPrevious) && <div className="sign-doc-layout">
        <nav aria-label="서명할 문서" className="sign-doc-list">
          <span className="sign-doc-list__title">{reviewingPrevious ? `이전 신청 문서 ${n}건 · 읽기 전용` : `서명할 문서 ${n}건 · 확인 ${readN}/${n}`}</span>
          {docs.map((item, index) => <button key={item.name} type="button" onClick={() => setCur(index)} aria-pressed={index === cur} className={`sign-doc-item${index === cur ? ' sign-doc-item--current' : ''}`}>
            <strong className="sign-doc-item__name">{item.name}</strong>
            <span className="sign-doc-item__ref">{item.ref}</span>
            <span className={`sign-doc-item__state${read[index] || reviewingPrevious ? ' sign-doc-item__state--read' : ''}`}>{reviewingPrevious ? '저장된 내용' : read[index] ? '확인 완료' : '확인 전'}</span>
          </button>)}
          {note && <p className="sign-doc-note">{note}</p>}
        </nav>

        <section aria-labelledby="sign-preview-title" className="sign-preview">
          <div className="sign-preview__head">
            <h2 id="sign-preview-title" ref={headingRef} tabIndex={-1} className="sign-preview__title">{currentDoc.name}</h2>
            <span className="sign-preview__ref">{currentDoc.ref}</span>
          </div>
          <div className="sign-preview__body">
            <div className="sign-paper">
              <div className="sign-paper__top"><strong className="sign-paper__bank">AX뱅크</strong></div>
              <strong className="sign-paper__title">{currentDoc.name}</strong>
              <dl className="sign-paper__fields">
                {currentDoc.fields.map(field => <div key={field.k} className="sign-paper__row">
                  <dt className="sign-paper__key">{field.k}</dt>
                  <dd className={`sign-paper__value${field.emphasis ? ' sign-paper__value--emphasis' : ''}`}>{field.v}</dd>
                </div>)}
              </dl>
              <p className="sign-paper__text">{currentDoc.body}</p>
              <div className="sign-paper__sign">신청인 {signer} <span className="sign-paper__sign-box">(서명)</span></div>
            </div>
            {reviewingPrevious
              ? <div role="status" className="sign-alert">이전 신청 내용을 읽기 전용으로 확인하고 있어요. 저장된 동의·서명·SMS 상태는 변경되지 않아요.</div>
              : <label className={`sign-check${read[cur] ? ' sign-check--on' : ''}`}>
                <input type="checkbox" checked={!!read[cur]} onChange={toggleRead} className="sign-check__input" />이 문서의 내용을 끝까지 확인했어요
              </label>}
            {reviewingPrevious && (path
              ? <div className="sign-done__image">
                <span className="sign-done__image-label">이번 화면에서 작성한 서명</span>
                <svg viewBox="0 0 600 220" role="img" aria-label="이번 화면에서 작성한 서명" className="sign-done__svg"><path d={path} fill="none" stroke="#1E3A5F" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round"></path></svg>
              </div>
              : initialStep === 'otp' && <div className="sign-done__image">
                <span className="sign-done__image-label">서명</span>
                <p className="sign-done__note">서명 이미지 저장 완료 · 보안상 이 화면에서는 저장 이미지를 다시 표시하지 않아요.</p>
              </div>)}
          </div>
        </section>
      </div>}

      {step === 'sign' && <section aria-labelledby="sign-pad-title" className="sign-card">
        <div className="sign-card__head">
          <h2 id="sign-pad-title" ref={headingRef} tabIndex={-1} className="sign-card__title">손글씨 서명</h2>
          <span className="sign-card__sub">아래 칸에 {signer}님의 서명을 그려 주세요. 문서 {n}건에 같은 서명이 들어가요.</span>
        </div>
        <SignaturePad strokes={strokes} onChange={onStrokes} disabled={signatureLocked || savingSignature} overridePath={demo ? demoPath : undefined} />
        <div className="sign-actions">
          <button type="button" onClick={clearSignature} disabled={signatureLocked || savingSignature} className="sign-button sign-button--plain">다시 쓰기</button>
        </div>
        {tooShort && pointCount < MIN_POINTS && <span role="alert" className="sign-error-text">서명이 너무 짧아요. 이름이 알아볼 수 있게 다시 써 주세요.</span>}
        <label className={`sign-check${agree ? ' sign-check--on' : ''}`}>
          <input type="checkbox" checked={agree} disabled={signatureLocked || savingSignature} onChange={() => setAgree(value => !value)} className="sign-check__input" />확인한 문서 {n}건에 위 서명을 넣는 데 동의해요
        </label>
        {signatureError && <div role="alert" className="sign-alert">{signatureError}</div>}
      </section>}

      {step === 'otp' && !reviewingPrevious && !working && !completeFailed && <section aria-labelledby="sign-otp-title" className="sign-card sign-card--narrow">
        <h2 id="sign-otp-title" ref={headingRef} tabIndex={-1} className="sign-card__title">휴대폰 본인 확인</h2>
        <dl className="sign-info">
          <dt className="sign-info__key">이름</dt><dd className="sign-info__value">{signer}</dd>
          {issue && <><dt className="sign-info__key">휴대폰</dt><dd className="sign-info__value">{issue.maskedPhone} <span className="sign-info__sub">등록된 번호 · 변경은 영업점에서</span></dd></>}
        </dl>
        {!issue && <button type="button" onClick={sendCode} disabled={issuing} className="sign-button sign-button--primary">인증번호 받기</button>}
        {issue && <div className="sign-otp">
          <label htmlFor="sign-otp-input" className="sign-otp__label">인증번호 6자리</label>
          <div className="sign-otp__row">
            <input id="sign-otp-input" type="text" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code}
              disabled={codeLocked || verifying}
              onChange={e => { setCode(e.target.value.replace(/\D/g, '').slice(0, 6)); if (otpMessage?.tone === 'error' && !codeLocked) setOtpMessage(null) }}
              aria-invalid={codeInvalid} aria-describedby="sign-otp-msg"
              className={`sign-otp__input${codeInvalid ? ' sign-otp__input--invalid' : ''}`} />
            <span className="sign-otp__timer" aria-label={`남은 시간 ${formatClock(remainingSec)}`}>{formatClock(remainingSec)}</span>
            <button type="button" onClick={sendCode} disabled={issuing || resendLeft > 0} className="sign-otp__resend">{resendLeft > 0 ? `다시 받기 · ${resendLeft}초 후` : '다시 받기'}</button>
          </div>
        </div>}
        <span id="sign-otp-msg" role={otpStatusError ? 'alert' : 'status'} className={`sign-otp__msg${otpStatusError ? ' sign-otp__msg--error' : ''}`}>{otpStatusText}</span>
        <p className="sign-otp__policy">
          인증번호는 서버가 만들어 등록된 휴대폰으로 문자(SOLAPI)를 보내요. {ttlSec !== null ? `${formatDuration(ttlSec)} 안에 입력해 주세요.` : '받은 인증번호는 정해진 시간 안에 입력해 주세요.'} {SMS_MAX_ATTEMPTS}회 틀리면 새 번호를 받아야 하고, 다시 받기는 {SMS_RESEND_COOLDOWN_SEC}초 뒤에 할 수 있어요.
          {adapter.devHint && <span className="sign-otp__dev">{adapter.devHint}</span>}
        </p>
        <button type="button" onClick={() => setReviewingPrevious(true)} className="sign-button sign-button--plain">이전 내용 확인</button>
      </section>}

      {working && <section role="status" className="sign-working">
        <span className="sign-working__spinner" aria-hidden="true"></span>
        <h2 ref={headingRef} tabIndex={-1} className="sign-working__title">서명을 문서에 넣고 있어요</h2>
        <span className="sign-working__sub">{afterText}</span>
      </section>}

      {completeFailed && !working && <section className="sign-failed">
        <h2 ref={headingRef} tabIndex={-1} className="sign-sr-only">처리 실패</h2>
        <div role="alert" className="sign-alert sign-alert--row">
          <span className="sign-alert__text">서명은 완료됐지만 처리 중에 문제가 생겼어요. 다시 시도해 주세요.</span>
          <button type="button" onClick={() => verification && void runComplete(verification)} className="sign-alert__retry">다시 시도</button>
        </div>
      </section>}

      {step === 'done' && <section className="sign-done">
        <div className="sign-done__head">
          <span className="sign-done__icon"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12l5 5 9-10"></path></svg></span>
          <div className="sign-done__titles">
            <h2 ref={headingRef} tabIndex={-1} className="sign-done__title">서명과 문자 인증을 마쳤어요</h2>
            <span className="sign-done__sub">문서 {n}건 · {signedAt ? formatStamp(signedAt) : ''}</span>
          </div>
        </div>
        <div className="sign-done__body">
          <dl className="sign-info sign-done__info">
            <dt className="sign-info__key">서명자</dt><dd className="sign-info__plain">{signer} · {issue?.maskedPhone} 인증</dd>
            <dt className="sign-info__key">서명 시각</dt><dd className="sign-info__value">{signedAt ? formatStamp(signedAt) : ''}</dd>
            <dt className="sign-info__key">방식</dt><dd className="sign-info__plain">화면 서명 + SMS 인증번호 (서버 검증)</dd>
          </dl>
          {completedSignaturePreview(path)
            ? <div className="sign-done__image">
              <span className="sign-done__image-label">이번 화면에서 작성한 서명</span>
              <svg viewBox="0 0 600 220" role="img" aria-label="이번 화면에서 작성한 서명" className="sign-done__svg"><path d={path} fill="none" stroke="#1E3A5F" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round"></path></svg>
            </div>
            : <div className="sign-done__image">
              <span className="sign-done__image-label">서명</span>
              <p className="sign-done__note">서명 이미지 저장 완료 · 보안상 이 화면에서는 저장 이미지를 다시 표시하지 않아요.</p>
            </div>}
        </div>
        <p className="sign-done__note">{storesSignature ? '서명 이미지는 서명 기록으로 서버에 저장돼요.' : '서명 이미지는 서버에 저장하지 않아요.'} 서버는 동의 기록과 SMS 인증 결과를 다시 확인한 뒤에만 {doneLabel} 단계로 넘겨요.</p>
        <div className="sign-done__actions">{onDone
          ? <button type="button" onClick={onDone} className="sign-button sign-button--primary">{doneLabel}</button>
          : <Link to={doneHref ?? '/'} className="sign-button sign-button--primary sign-button--link">{doneLabel}</Link>}</div>
      </section>}

      {showBar && <div className="sign-bar">
        <span aria-live="polite" className={`sign-bar__hint${canNext ? '' : ' sign-bar__hint--warn'}`}>{hint}</span>
        <Link to={backHref} className="sign-button sign-button--plain sign-button--link sign-bar__button">서명 취소</Link>
        {step === 'sign' && <button type="button" onClick={() => setStep('doc')} disabled={savingSignature} className="sign-button sign-button--plain sign-bar__button">이전</button>}
        <button type="button" onClick={next} disabled={!canNext} className="sign-button sign-button--primary sign-bar__button">{nextLabel}</button>
      </div>}

      {reviewingPrevious && <div className="sign-bar">
        <span aria-live="polite" className="sign-bar__hint">조회만으로 서버의 진행 단계나 인증 상태는 변경되지 않아요.</span>
        <button type="button" onClick={() => setReviewingPrevious(false)} className="sign-button sign-button--primary sign-bar__button">인증 계속하기</button>
      </div>}

      <p className="sign-footnote">서명은 화면에서 직접 입력하고, SMS 인증은 휴대폰 소지 확인이에요. 서명과 SMS 인증을 마치지 않으면 서버가 실행·제출을 거부해요.</p>
    </main>
    <SiteFooter />
  </div>
}
