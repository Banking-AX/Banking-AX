import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { b02Api, taskBffEnabled, type B02ExecuteResult, type B02PrepareResult } from '../../api/taskApi'

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
  const [assessment, setAssessment] = useState<B02PrepareResult | null>(null)
  const [result, setResult] = useState<B02ExecuteResult | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const item = Number(workItemId); const account = Number(accountId); const revision = Number(version)
  const requestId = (suffix: string) => `b02-ui-${workItemId}-${suffix}`
  const taxValue = taxed === 'unknown' ? null : taxed === 'exists'
  const validIds = Number.isSafeInteger(item) && item > 0 && Number.isSafeInteger(account) && account > 0 && Number.isSafeInteger(revision) && revision >= 0

  const issue = async () => {
    if (!validIds || !consent || !signed) return
    setBusy(true); setMessage(null)
    try {
      const prepared = await b02Api.prepare({ requestId: requestId('prepare'), account, waive, taxed: taxValue })
      if (!prepared.data) throw new Error('해지 조건 확인 결과가 비어 있습니다.')
      setAssessment(prepared.data)
      if (prepared.data.manual || prepared.data.rate === 'DOCUMENT_REQUIRED') {
        setMessage(prepared.data.manual
          ? `직원/영업점 확인이 필요한 조건입니다 (${prepared.data.rule}). OTP 발급과 자동 해지는 진행하지 않습니다.`
          : `과세사실 확인이 필요합니다 (${prepared.data.rule}). 확인 후 조건을 다시 조회해 주세요.`)
        return
      }
      const response = await b02Api.issueOtp(account, { requestId: requestId('issue'), version: revision, consent, signed, phone: phone || null, workItemId: item })
      if (!response.data?.challengeId) throw new Error('OTP challenge가 반환되지 않았습니다.')
      setChallengeId(response.data.challengeId); setPhase('OTP'); setMessage('서버가 OTP를 발급했습니다. 개발 환경에서는 등록된 Mock SMS 정책을 사용합니다.')
    } catch (e) { setMessage(e instanceof Error ? e.message : 'OTP 발급에 실패했습니다.') } finally { setBusy(false) }
  }
  const verify = async () => {
    if (!challengeId || !otp) return
    setBusy(true); setMessage(null)
    try { const response = await b02Api.verifyOtp(challengeId, otp, requestId('verify')); if (!response.data?.bindingId) throw new Error('OTP binding이 반환되지 않았습니다.'); setBindingId(response.data.bindingId); setMessage('OTP가 검증되었습니다. 최종 실행을 확인해 주세요.') } catch (e) { setMessage(e instanceof Error ? e.message : 'OTP 검증에 실패했습니다.') } finally { setBusy(false) }
  }
  const execute = async () => {
    if (!bindingId || !validIds) return
    setBusy(true); setMessage(null)
    try { const response = await b02Api.execute(account, { requestId: requestId('execute'), version: revision, waive, taxed: taxValue, binding: bindingId, workItemId: item }); if (!response.data) throw new Error('실행 결과가 비어 있습니다.'); setResult(response.data); setPhase('EXECUTED'); setMessage('모의 해지 실행 결과를 받았습니다. DB/Workflow 완료 상태는 별도 조회 API가 없어 이 화면에서 재조회할 수 없습니다.') } catch (e) { setMessage(e instanceof Error ? e.message : '해지 실행에 실패했습니다.') } finally { setBusy(false) }
  }

  return <section>
    <h1>청년 주택드림 청약통장 모의 해지</h1><p>WorkItem ID: {workItemId ?? '미지정'} (trusted route parameter). 계좌 ID와 WorkItem ID는 별도로 전송됩니다.</p>
    {!workItemId && <p role="alert">업무 시작 화면에서 전달된 WorkItem이 필요합니다. 이 페이지는 자체적으로 WorkItem을 만들지 않습니다.</p>}
    {!taskBffEnabled && <p role="status">로그인 BFF 연결 대기 중입니다. 내부 Gateway Token은 브라우저에 제공되지 않습니다.</p>}
    <label>계좌 ID <input inputMode="numeric" value={accountId} onChange={e => setAccountId(e.target.value)} /></label>
    <label>계좌 version <input inputMode="numeric" value={version} onChange={e => setVersion(e.target.value)} /></label>
    <label>등록 전화번호 <input value={phone} onChange={e => setPhone(e.target.value)} /></label>
    <label><input type="checkbox" checked={waive} onChange={e => setWaive(e.target.checked)} /> 우대금리 포기</label>
    <label>과세 사실 <select value={taxed} onChange={e => setTaxed(e.target.value as typeof taxed)}><option value="unknown">확인 필요</option><option value="none">없음</option><option value="exists">있음</option></select></label>
    <label><input type="checkbox" checked={consent} onChange={e => setConsent(e.target.checked)} /> 필수 동의</label>
    <label><input type="checkbox" checked={signed} onChange={e => setSigned(e.target.checked)} /> 모의 서명 완료</label>
    <button type="button" disabled={!taskBffEnabled || busy || !validIds || !consent || !signed || taxed === 'exists'} onClick={issue}>OTP 발급</button>
    {assessment && <p role={assessment.manual || assessment.rate === 'DOCUMENT_REQUIRED' ? 'alert' : 'status'}>Spring 사전 판정: {assessment.rate} · {assessment.rule}{assessment.manual ? ' · 직원/영업점 확인 필요' : ''}</p>}
    {phase === 'OTP' && <><label>OTP <input value={otp} onChange={e => setOtp(e.target.value)} /></label><button type="button" disabled={!taskBffEnabled || busy || !otp} onClick={verify}>OTP 검증</button><button type="button" disabled={!taskBffEnabled || busy || !bindingId} onClick={execute}>모의 해지 실행</button></>}
    {result && <section><h2>모의 해지 응답</h2><p>상태 {result.status} · 적용 이율 {result.rate} · 규칙 {result.rule}</p><p>계좌 {result.account} · WorkItem {item}</p></section>}
    {message && <p role={phase === 'EXECUTED' ? 'status' : 'alert'}>{message}</p>}
  </section>
}
