import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { b02Api, taskBffEnabled, type B02Assessment, type B02Execution, type CustomerAccountOption } from '../../api/taskApi'
import { toUserMessageOr } from '../../api/errorMessages'
import { accountStatusLabel, b02AssessmentLabel } from '../../ui/customerLabels'
import '../t2/customer-pages.css'

type Phase = 'INPUT' | 'OTP' | 'EXECUTED'

/** B-02 interaction state. Server-side BFF derives the customer and owns gateway authentication. */
export function HousingSavingsClosePage() {
  const { workItemId } = useParams()
  const [accountId, setAccountId] = useState('')
  const [version, setVersion] = useState('0')
  const [phone, setPhone] = useState('')
  const [waive, setWaive] = useState(false)
  const [taxed, setTaxed] = useState<'unknown' | 'none' | 'exists'>('unknown')
  const [consent, setConsent] = useState(false)
  const [signed, setSigned] = useState(false)
  const [otp, setOtp] = useState('')
  const [challengeId, setChallengeId] = useState<string | null>(null)
  const [bindingId, setBindingId] = useState<string | null>(null)
  const [phase, setPhase] = useState<Phase>('INPUT')
  const [assessment, setAssessment] = useState<B02Assessment | null>(null)
  const [execution, setExecution] = useState<B02Execution | null>(null)
  const [accounts, setAccounts] = useState<CustomerAccountOption[]>([])
  const [accountsLoaded, setAccountsLoaded] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const item = Number(workItemId); const account = Number(accountId); const revision = Number(version)
  const requestId = (suffix: string) => `b02-ui-${workItemId}-${suffix}`
  const taxValue = taxed === 'unknown' ? null : taxed === 'exists'
  const validIds = Number.isSafeInteger(item) && item > 0 && Number.isSafeInteger(account) && account > 0 && Number.isSafeInteger(revision) && revision >= 0
  const accountLabel = (option: CustomerAccountOption) => `${option.productName} · ${option.maskedAccountNo} · ${accountStatusLabel(option.status)}`

  useEffect(() => {
    if (!taskBffEnabled) { setAccountsLoaded(true); return }
    let active = true
    b02Api.accounts(`b02-accounts-${crypto.randomUUID()}`).then(response => {
      if (!active) return
      const owned = response.data ?? []; setAccounts(owned); const first = owned.find(option => option.status === 'ACTIVE') ?? owned[0]
      if (first) { setAccountId(String(first.accountId)); setVersion(String(first.version ?? 0)) }
    }).catch(cause => { if (active) setMessage(toUserMessageOr(cause, '청약계좌 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.')) }).finally(() => { if (active) setAccountsLoaded(true) })
    return () => { active = false }
  }, [])

  const issue = async () => {
    if (!validIds) return
    setBusy(true); setMessage(null)
    try {
      const prepared = await b02Api.prepare({ requestId: requestId('prepare'), account, waive, taxed: taxValue })
      if (!prepared.data) throw new Error('사전판정 결과가 비어 있습니다.')
      setAssessment(prepared.data)
      if (prepared.data.manual || prepared.data.rate === 'DOCUMENT_REQUIRED') { setMessage(`${b02AssessmentLabel(prepared.data.rate)} 이 상태에서는 인증번호 발급과 해지 실행을 진행할 수 없습니다.`); return }
      if (!consent || !signed) { setMessage('사전판정이 완료되었습니다. 필수 동의와 모의 서명 후 OTP를 발급해 주세요.'); return }
      const response = await b02Api.issueOtp(account, { requestId: requestId('issue'), version: revision, consent, signed, phone: phone || null, workItemId: item })
      if (!response.data?.challengeId) throw new Error('OTP challenge가 반환되지 않았습니다.')
      setChallengeId(response.data.challengeId); setPhase('OTP'); setMessage('서버가 OTP를 발급했습니다. 개발 환경에서는 등록된 Mock SMS 정책을 사용합니다.')
    } catch (e) { setMessage(toUserMessageOr(e, '인증번호 발급에 실패했습니다. 잠시 후 다시 시도해 주세요.')) } finally { setBusy(false) }
  }
  const verify = async () => {
    if (!challengeId || !otp) return
    setBusy(true); setMessage(null)
    try { const response = await b02Api.verifyOtp(challengeId, otp, requestId('verify')); if (!response.data?.bindingId) throw new Error('인증 결과를 확인하지 못했습니다.'); setBindingId(response.data.bindingId); setMessage('인증이 완료되었습니다. 해지 실행 전 내용을 다시 확인해 주세요.') } catch (e) { setMessage(toUserMessageOr(e, '인증번호 확인에 실패했습니다. 잠시 후 다시 시도해 주세요.')) } finally { setBusy(false) }
  }
  const execute = async () => {
    if (!bindingId || !validIds || !assessment || assessment.manual || assessment.rate === 'DOCUMENT_REQUIRED') return
    setBusy(true); setMessage(null)
    try { await b02Api.execute(account, { requestId: requestId('execute'), version: revision, waive, taxed: taxValue, binding: bindingId, workItemId: item }); setPhase('EXECUTED'); setMessage('해지 요청이 완료되었습니다. 계좌 상태는 서버에서 다시 확인합니다.') } catch (e) { setMessage(toUserMessageOr(e, '해지 실행에 실패했습니다. 잠시 후 다시 시도해 주세요.')) } finally { setBusy(false) }
  }

  if (accountsLoaded && taskBffEnabled) return <main className="t2-page"><header className="t2-hero"><span className="t2-badge">B-02</span><h1>청약통장 모의 해지</h1><p>내 청약계좌를 선택하고 서버의 사전판정부터 진행합니다.</p></header><section className="t2-card"><label className="t2-field">청약계좌<select value={accountId} onChange={e => { const selected = accounts.find(option => option.accountId === Number(e.target.value)); setAccountId(e.target.value); setVersion(String(selected?.version ?? 0)); setAssessment(null); setExecution(null); setBindingId(null); setChallengeId(null) }}><option value="">계좌 선택</option>{accounts.map(option => <option key={option.accountId} value={option.accountId} disabled={option.status !== 'ACTIVE'}>{option.productName} · {option.maskedAccountNo} · {option.status}</option>)}</select></label>{accounts.length === 0 && <p className="t2-banner">조회 가능한 청약계좌가 없습니다.</p>}<p className="t2-muted">선택 계좌 revision: {version}</p><label className="t2-field">과세 사실<select value={taxed} onChange={e => { setTaxed(e.target.value as typeof taxed); setAssessment(null) }}><option value="unknown">확인 필요</option><option value="none">없음</option><option value="exists">있음</option></select></label><label className="t2-check"><input type="checkbox" checked={waive} onChange={e => setWaive(e.target.checked)} />우대금리 포기</label><div className="t2-actions"><button className="t2-button" disabled={busy || !accountId} onClick={issue}>사전판정</button></div>{assessment && <><p className={assessment.manual || assessment.rate === 'DOCUMENT_REQUIRED' ? 't2-banner t2-banner--warn' : 't2-banner'}>서버 판정: {assessment.rate} · {assessment.rule}</p>{!assessment.manual && assessment.rate !== 'DOCUMENT_REQUIRED' && <><label className="t2-check"><input type="checkbox" checked={consent} onChange={e => setConsent(e.target.checked)} />필수 동의</label><label className="t2-check"><input type="checkbox" checked={signed} onChange={e => setSigned(e.target.checked)} />모의 서명 완료</label><button className="t2-button" disabled={busy || !consent || !signed} onClick={issue}>SMS 인증번호 발급</button></>}</>}{phase === 'OTP' && <div className="t2-actions"><input aria-label="인증번호" value={otp} onChange={e => setOtp(e.target.value)} /><button className="t2-button t2-button--secondary" disabled={!otp || busy} onClick={verify}>인증 확인</button><button className="t2-button" disabled={!bindingId || busy} onClick={execute}>모의 해지 실행</button></div>}{execution && <p className="t2-banner">서버 결과: {execution.status} · {execution.rate}</p>}{message && <p className="t2-banner">{message}</p>}</section></main>

  return <main className="t2-page"><header className="t2-hero"><span className="t2-badge">B-02</span><h1>청약통장 모의 해지</h1><p>사전판정 후 가능한 경우에만 동의·모의서명·SMS 인증을 진행합니다.</p></header><section className="t2-card"><div className="t2-grid"><label className="t2-field">계좌 ID<input inputMode="numeric" value={accountId} onChange={e => { setAccountId(e.target.value); setAssessment(null); setExecution(null) }} /></label><label className="t2-field">계좌 revision<input inputMode="numeric" value={version} onChange={e => setVersion(e.target.value)} /></label><label className="t2-field">등록 전화번호<input value={phone} onChange={e => setPhone(e.target.value)} /></label><label className="t2-field">과세 사실<select value={taxed} onChange={e => { setTaxed(e.target.value as typeof taxed); setAssessment(null) }}><option value="unknown">확인 필요</option><option value="none">없음</option><option value="exists">있음</option></select></label></div><label className="t2-check"><input type="checkbox" checked={waive} onChange={e => setWaive(e.target.checked)} />우대금리 포기</label><div className="t2-actions"><button className="t2-button" type="button" disabled={!taskBffEnabled || busy || !validIds} onClick={issue}>사전판정 및 OTP 발급</button></div>{assessment && <p className={assessment.manual || assessment.rate === 'DOCUMENT_REQUIRED' ? 't2-banner t2-banner--warn' : 't2-banner'}>서버 판정: <strong>{assessment.rate}</strong> · {assessment.rule}</p>}{assessment && !assessment.manual && assessment.rate !== 'DOCUMENT_REQUIRED' && <><label className="t2-check"><input type="checkbox" checked={consent} onChange={e => setConsent(e.target.checked)} />필수 동의</label><label className="t2-check"><input type="checkbox" checked={signed} onChange={e => setSigned(e.target.checked)} />모의 서명 완료</label></>}{phase === 'OTP' && <div className="t2-actions"><input aria-label="인증번호" value={otp} onChange={e => setOtp(e.target.value)} /><button className="t2-button t2-button--secondary" disabled={busy || !otp} onClick={verify}>인증 확인</button><button className="t2-button" disabled={busy || !bindingId} onClick={execute}>모의 해지 실행</button></div>}{execution && <p className="t2-banner">서버 실행 결과: {execution.status} · {execution.rate} · {execution.rule}</p>}{message && <p className="t2-banner" role="status">{message}</p>}<p className="t2-muted">계좌 목록 API가 없어 계좌 ID와 revision을 직접 입력합니다. 실제 계좌 상태를 화면에서 변경하지 않습니다.</p></section></main>

  return <section>
    <h1>청년 주택드림 청약통장 모의 해지</h1><p>WorkItem ID: {workItemId ?? '미지정'}. 계좌 ID와 WorkItem ID는 별도로 전송됩니다.</p>
    {!taskBffEnabled && <p role="status">로그인 BFF 연결 대기 중입니다. 내부 Gateway Token은 브라우저에 제공되지 않습니다.</p>}
    <label>계좌 ID <input inputMode="numeric" value={accountId} onChange={e => setAccountId(e.target.value)} /></label>
    <label>계좌 version <input inputMode="numeric" value={version} onChange={e => setVersion(e.target.value)} /></label>
    <label>등록 전화번호 <input value={phone} onChange={e => setPhone(e.target.value)} /></label>
    <label><input type="checkbox" checked={waive} onChange={e => setWaive(e.target.checked)} /> 우대금리 포기</label>
    <label>과세 사실 <select value={taxed} onChange={e => setTaxed(e.target.value as typeof taxed)}><option value="unknown">확인 필요</option><option value="none">없음</option><option value="exists">있음</option></select></label>
    <label><input type="checkbox" checked={consent} onChange={e => setConsent(e.target.checked)} /> 필수 동의</label>
    <label><input type="checkbox" checked={signed} onChange={e => setSigned(e.target.checked)} /> 모의 서명 완료</label>
    <button type="button" disabled={!taskBffEnabled || busy || !validIds || !consent || !signed || taxed === 'exists'} onClick={issue}>OTP 발급</button>
    {phase === 'OTP' && <><label>OTP <input value={otp} onChange={e => setOtp(e.target.value)} /></label><button type="button" disabled={!taskBffEnabled || busy || !otp} onClick={verify}>OTP 검증</button><button type="button" disabled={!taskBffEnabled || busy || !bindingId} onClick={execute}>모의 해지 실행</button></>}
    {message && <p role={phase === 'EXECUTED' ? 'status' : 'alert'}>{message}</p>}
  </section>
}
