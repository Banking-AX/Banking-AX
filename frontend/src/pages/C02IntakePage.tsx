import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { c02Api, c02MockSmsEnabled, taskBffEnabled, type C02Document, type C02DocumentSlot, type C02Heir, type C02OcrSnapshot, type C02Prepared, type C02Review } from '../api/taskApi'

type HeirForm = Omit<C02Heir, 'birthDate' | 'delegationReceiptNo'> & { birthDate: string; delegationReceiptNo: string }
type CaseSession = { caseId: number; revision: number; caseStatus: string; reviewStatus: string | null; requiredDocuments: C02DocumentSlot[] }
const emptyHeir = (applicant = false): HeirForm => ({ personRef: crypto.randomUUID(), name: '', birthDate: '', relationship: '', applicant, higherOrderOrSubrogation: false, visitStatus: 'ATTENDING', delegationMethod: 'NONE', delegationReceiptNo: '' })
const slotKey = (slot: C02DocumentSlot) => `${slot.documentTypeCode}:${slot.personRef ?? 'COMMON'}`
const requestId = (scope: string) => `c02-ui-${scope}-${crypto.randomUUID()}`

/** C-02 customer actions use the authenticated JWT BFF; ownership remains server-verified. */
export function C02IntakePage() {
  const { workItemId } = useParams()
  const itemId = Number(workItemId)
  const storageKey = `c02-case-work-item-${workItemId ?? ''}`
  const [decedentName, setDecedentName] = useState('')
  const [deathDate, setDeathDate] = useState('')
  const [heirs, setHeirs] = useState<HeirForm[]>([emptyHeir(true)])
  const [caseSession, setCaseSession] = useState<CaseSession | null>(null)
  const [documents, setDocuments] = useState<C02Document[]>([])
  const [ocrSnapshot, setOcrSnapshot] = useState<C02OcrSnapshot | null>(null)
  const [ocrError, setOcrError] = useState<string | null>(null)
  const [review, setReview] = useState<C02Review | null>(null)
  const [prepared, setPrepared] = useState<C02Prepared | null>(null)
  const [files, setFiles] = useState<Record<string, File | undefined>>({})
  const [consented, setConsented] = useState(false)
  const [signatureComplete, setSignatureComplete] = useState(false)
  const [challenge, setChallenge] = useState<{ challengeId: string; maskedPhone: string; expiresAt: string } | null>(null)
  const [otp, setOtp] = useState('')
  const [bindingId, setBindingId] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const requiredSlots = useMemo(() => caseSession?.requiredDocuments ?? prepared?.requiredDocuments ?? [], [caseSession, prepared])
  const latestRequiredUploaded = requiredSlots.filter(slot => slot.requirement === 'REQUIRED').every(slot => documents.some(doc => doc.documentSlotKey === slotKey(slot) && doc.caseRevision <= (caseSession?.revision ?? -1)))
  const hasCurrentReview = Boolean(review && caseSession && review.revision === caseSession.revision)

  const persistCase = (next: CaseSession | null) => {
    setCaseSession(next)
    if (next) sessionStorage.setItem(storageKey, JSON.stringify(next))
    else sessionStorage.removeItem(storageKey)
  }

  const reloadCaseData = async (current: CaseSession) => {
    const first = await c02Api.snapshot(current.caseId, requestId('snapshot'))
    const snapshot = first.data
    if (!snapshot || snapshot.caseId !== current.caseId || snapshot.workItemId !== itemId) {
      throw new Error('저장된 Case가 현재 서버 발급 WorkItem과 일치하지 않습니다.')
    }
    const ocrResponse = await c02Api.customerOcr(snapshot.caseId, snapshot.revision, requestId('ocr'))
      .then(value => ({ value, error: null as string | null }))
      .catch(cause => ({ value: null, error: cause instanceof Error ? cause.message : 'OCR 상태를 불러오지 못했습니다.' }))
    const latest = await c02Api.snapshot(current.caseId, requestId('snapshot-check'))
    if (!latest.data || latest.data.revision !== snapshot.revision || latest.data.workItemId !== snapshot.workItemId) {
      throw new Error('Case가 조회 중 변경됐습니다. 새로고침해 최신 revision을 다시 불러와 주세요.')
    }
    const restored: CaseSession = {
      caseId: snapshot.caseId, revision: snapshot.revision, caseStatus: snapshot.caseStatus,
      reviewStatus: snapshot.reviewStatus, requiredDocuments: snapshot.requiredDocuments ?? [],
    }
    persistCase(restored)
    setDocuments(snapshot.documents ?? [])
    setReview(snapshot.precheck ?? null)
    const ocr = ocrResponse.value
    const ocrData = ocr?.data
    const ocrIsCurrent = Boolean(ocrData && ocrData.revision === snapshot.revision && ocrData.documents.every(extraction =>
      extraction.caseRevision <= snapshot.revision && snapshot.documents.some(document =>
        document.documentFileId === extraction.documentFileId && document.caseRevision === extraction.caseRevision,
      ),
    ))
    setOcrSnapshot(ocrIsCurrent && ocrData ? ocrData : null)
    setOcrError(ocrResponse.error ?? (ocr && !ocrIsCurrent ? 'OCR 결과 revision이 현재 Case 문서와 일치하지 않습니다.' : null))
  }

  useEffect(() => {
    if (!taskBffEnabled || !Number.isSafeInteger(itemId) || itemId <= 0) return
    try {
      const raw = sessionStorage.getItem(storageKey)
      if (!raw) return
      const restored = JSON.parse(raw) as CaseSession
      if (restored.caseId > 0 && restored.requiredDocuments) {
        setCaseSession(restored)
        void reloadCaseData(restored).catch(cause => setError(cause instanceof Error ? cause.message : '저장된 Case를 불러오지 못했습니다.'))
      }
    } catch {
      sessionStorage.removeItem(storageKey)
    }
    // The session key and work item identify only the current browser's Case context;
    // Spring still verifies JWT ownership for every request.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itemId, storageKey])

  const updateHeir = (index: number, patch: Partial<HeirForm>) => setHeirs(current => current.map((heir, i) => i === index ? { ...heir, ...patch } : heir))

  const prepare = async () => {
    if (!Number.isSafeInteger(itemId) || itemId <= 0 || !decedentName.trim() || !deathDate || heirs.length === 0 || heirs.some(heir => !heir.name.trim() || !heir.relationship.trim())) {
      setError('업무 ID, 피상속인 사망일, 상속인 성명과 관계를 확인해 주세요.')
      return
    }
    setBusy(true); setError(null); setNotice(null)
    try {
      const response = await c02Api.prepare({
        requestId: requestId('prepare'), workItemId: itemId, registeredPhone: null,
        decedent: { name: decedentName.trim(), birthDate: null, deathDate, depositTotal: 0 },
        heirs: heirs.map(heir => ({ ...heir, name: heir.name.trim(), relationship: heir.relationship.trim(), birthDate: heir.birthDate || null, delegationReceiptNo: heir.delegationReceiptNo || null })),
        documentStatuses: {},
      })
      const preparedData = response.data
      if (!preparedData) throw new Error('Case 준비 응답이 비어 있습니다.')
      setPrepared(preparedData)
      const view = preparedData.caseView
      if (!view) {
        setNotice(preparedData.submission?.outOfScope?.length ? `접수 범위 확인이 필요합니다: ${preparedData.submission.outOfScope.join(', ')}` : '입력 검증을 마쳤지만 Case가 생성되지 않았습니다.')
        return
      }
      const next: CaseSession = { caseId: view.caseId, revision: view.revision, caseStatus: view.caseStatus, reviewStatus: view.reviewStatus, requiredDocuments: preparedData.requiredDocuments ?? [] }
      persistCase(next)
      setDocuments([]); setReview(null); setOcrSnapshot(null); setOcrError(null); setConsented(false); setSignatureComplete(false); setBindingId(null); setChallenge(null)
      setNotice('Case가 준비되었습니다. 필수 슬롯에 서류를 업로드해 주세요.')
      await reloadCaseData(next)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'C-02 Case 준비에 실패했습니다.') }
    finally { setBusy(false) }
  }

  const upload = async (slot: C02DocumentSlot) => {
    if (!caseSession) return
    const file = files[slotKey(slot)]
    if (!file) { setError('업로드할 파일을 선택해 주세요.'); return }
    setBusy(true); setError(null); setNotice(null)
    try {
      const response = await c02Api.uploadDocument(caseSession.caseId, caseSession.revision, requestId('upload'), slotKey(slot), file)
      const next = { ...caseSession, revision: response.data?.revision ?? caseSession.revision + 1, reviewStatus: response.data?.reviewStatus ?? caseSession.reviewStatus }
      persistCase(next)
      setFiles(current => ({ ...current, [slotKey(slot)]: undefined }))
      setConsented(false); setSignatureComplete(false); setBindingId(null); setChallenge(null); setOtp('')
      await reloadCaseData(next)
      setNotice(`파일이 저장됐습니다. 현재 Case revision은 ${next.revision}입니다. OCR 상세 결과는 이 API 응답에 포함되지 않아 추출 완료로 표시하지 않습니다.`)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '문서 업로드에 실패했습니다.') }
    finally { setBusy(false) }
  }

  const saveConsentAndSignature = async () => {
    if (!caseSession || !consented || !signatureComplete) return
    setBusy(true); setError(null)
    try {
      await c02Api.recordConsent(caseSession.caseId, { revision: caseSession.revision, requestId: requestId('consent'), consented, mockSignatureCompleted: signatureComplete })
      setNotice('동의와 모의 서명 완료 상태가 서버에 전달됐습니다. 실제 법적 전자서명은 아닙니다.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '동의 기록에 실패했습니다.') }
    finally { setBusy(false) }
  }

  const issueOtp = async () => {
    if (!caseSession || !consented || !signatureComplete || !c02MockSmsEnabled) return
    setBusy(true); setError(null)
    try {
      await c02Api.recordConsent(caseSession.caseId, { revision: caseSession.revision, requestId: requestId('consent'), consented, mockSignatureCompleted: signatureComplete })
      const response = await c02Api.issueOtp(caseSession.caseId, { revision: caseSession.revision, requestId: requestId('otp-issue') })
      if (!response.data?.challengeId) throw new Error('인증 challenge를 받지 못했습니다.')
      setChallenge(response.data); setBindingId(null); setNotice(`테스트용 문자 발송 요청이 등록됐습니다. 대상 번호: ${response.data.maskedPhone}`)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'OTP 요청에 실패했습니다.') }
    finally { setBusy(false) }
  }

  const verifyOtp = async () => {
    if (!caseSession || !challenge || !otp || !c02MockSmsEnabled) return
    setBusy(true); setError(null)
    try {
      const response = await c02Api.verifyOtp({ requestId: requestId('otp-verify'), challengeId: challenge.challengeId, otp })
      if (!response.data?.bindingId) throw new Error('제출 바인딩을 받지 못했습니다.')
      setBindingId(response.data.bindingId); setNotice('OTP 검증 완료. 바인딩은 현재 revision에만 유효합니다.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'OTP 검증에 실패했습니다.') }
    finally { setBusy(false) }
  }

  const submit = async () => {
    if (!caseSession || !bindingId || !latestRequiredUploaded) return
    setBusy(true); setError(null)
    try {
      const response = await c02Api.submit(caseSession.caseId, { revision: caseSession.revision, requestId: requestId('submit'), bindingId })
      const result = response.data
      if (!result) throw new Error('제출 결과가 비어 있습니다.')
      const next = { ...caseSession, revision: result.revision, caseStatus: result.caseStatus, reviewStatus: result.reviewStatus }
      persistCase(next); setReview({ caseId: result.caseId, workItemId: result.workItemId, revision: result.revision, agentRunStatus: result.agentRunStatus, reviewStatus: result.reviewStatus, reviewItems: result.reviewItems ?? [], error: result.error, finalStaffDecision: false })
      setBindingId(null); setNotice(`제출 상태: ${result.caseStatus}. 직원 Agent 사전검토: ${result.agentRunStatus} / ${result.reviewStatus}. 직원 최종결정은 별도 단계입니다.`)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '접수 제출에 실패했습니다.') }
    finally { setBusy(false) }
  }

  return <section>
    <h1>C-02 상속예금 사전접수</h1>
    <p>WorkItem ID: {workItemId ?? '미지정'} · 고객 식별은 로그인 JWT에서 서버가 결정합니다.</p>
    {!taskBffEnabled && <p role="status">로그인 BFF 연결이 비활성화되어 있습니다. 세션 없이 Case를 만들거나 고객 ID를 임의로 전송하지 않습니다.</p>}
    {!caseSession && <>
      <label>피상속인 성명 <input value={decedentName} onChange={event => setDecedentName(event.target.value)} /></label>
      <label>사망일 <input type="date" value={deathDate} onChange={event => setDeathDate(event.target.value)} /></label>
      <h2>상속인</h2>
      {heirs.map((heir, index) => <fieldset key={heir.personRef}>
        <label>성명 <input value={heir.name} onChange={event => updateHeir(index, { name: event.target.value })} /></label>
        <label>관계 <input value={heir.relationship} onChange={event => updateHeir(index, { relationship: event.target.value })} /></label>
        <label>생년월일 <input type="date" value={heir.birthDate} onChange={event => updateHeir(index, { birthDate: event.target.value })} /></label>
        <label><input type="radio" checked={heir.applicant} onChange={() => setHeirs(current => current.map((value, i) => ({ ...value, applicant: i === index })))} /> 신청인</label>
        <label><input type="checkbox" checked={heir.higherOrderOrSubrogation} onChange={event => updateHeir(index, { higherOrderOrSubrogation: event.target.checked })} /> 상위 순위 또는 대습 상속인</label>
        <label>방문 여부 <select value={heir.visitStatus} onChange={event => updateHeir(index, { visitStatus: event.target.value as HeirForm['visitStatus'] })}><option value="ATTENDING">방문</option><option value="ABSENT">불참</option><option value="UNCONFIRMED_ABSENT">미확인 불참</option></select></label>
        <label>위임 방식 <select value={heir.delegationMethod} onChange={event => updateHeir(index, { delegationMethod: event.target.value as HeirForm['delegationMethod'] })}><option value="NONE">없음</option><option value="DOCUMENT">위임장</option><option value="RECEIPT">접수번호</option></select></label>
        {heir.delegationMethod === 'RECEIPT' && <label>위임 접수번호 <input value={heir.delegationReceiptNo} onChange={event => updateHeir(index, { delegationReceiptNo: event.target.value })} /></label>}
        <button type="button" disabled={busy || heirs.length <= 1} onClick={() => setHeirs(current => current.filter((_, i) => i !== index))}>상속인 삭제</button>
      </fieldset>)}
      <button type="button" disabled={busy} onClick={() => setHeirs(current => [...current, emptyHeir()])}>상속인 추가</button>
      <button type="button" disabled={!taskBffEnabled || busy} onClick={() => void prepare()}>{busy ? '준비 중…' : 'Case 준비 및 필수 서류 확인'}</button>
      {prepared?.submission && <div><p>필수 슬롯 검증: {prepared.submission.submittable ? '충족' : '보완 필요'}</p>{prepared.submission.errors.map(value => <p key={value}>{value}</p>)}{prepared.submission.missingDocuments.map(value => <p key={value}>누락: {value}</p>)}{prepared.submission.outOfScope.map(value => <p key={value}>범위 확인 필요: {value}</p>)}</div>}
    </>}

    {caseSession && <>
      <h2>Case {caseSession.caseId}</h2>
      <p><Link to={`/tasks/c02/cases/${caseSession.caseId}`}>Open saved Case details</Link></p>
      <p>상태 {caseSession.caseStatus} · revision {caseSession.revision} · Agent 검토 {caseSession.reviewStatus ?? '미실행'}</p>
      <h3>필수·조건부 문서 슬롯</h3>
      {requiredSlots.length === 0 ? <p>문서 슬롯 manifest를 불러오지 못했습니다.</p> : <ul>{requiredSlots.map(slot => {
        const key = slotKey(slot); const uploaded = documents.find(document => document.documentSlotKey === key)
        return <li key={key}>
          <strong>{slot.documentTypeCode}</strong> ({slot.requirement}) {slot.personRef ? `· 상속인 ${slot.personRef}` : ''} — {slot.note}
          {uploaded ? <span> · 업로드됨 v{uploaded.version}, revision {uploaded.caseRevision}, {uploaded.fileName}</span> : <span> · 미업로드</span>}
          <label>파일 <input type="file" disabled={busy || !taskBffEnabled} onChange={event => setFiles(current => ({ ...current, [key]: event.target.files?.[0] }))} /></label>
          <button type="button" disabled={!taskBffEnabled || busy || !files[key]} onClick={() => void upload(slot)}>업로드 / 보완 제출</button>
        </li>
      })}</ul>}
      <button type="button" disabled={!taskBffEnabled || busy} onClick={() => void reloadCaseData(caseSession).catch(cause => setError(cause instanceof Error ? cause.message : 'Case 자료를 불러오지 못했습니다.'))}>문서·검토 상태 새로고침</button>
      <h3>업로드 문서</h3>
      {documents.length === 0 ? <p>등록된 문서가 없습니다.</p> : <ul>{documents.map(document => {
        const extraction = ocrSnapshot?.revision === caseSession.revision ? ocrSnapshot.documents.find(item => item.documentFileId === document.documentFileId && item.caseRevision === caseSession.revision) : undefined
        return <li key={document.documentFileId}>
          {document.fileName} · {document.documentType} · {document.status} · revision {document.caseRevision}
          {document.caseRevision === caseSession.revision && <div>
            <strong>OCR: {extraction ? `${extraction.method} · ${extraction.status}` : ocrError ? '상태 조회 실패' : '현재 revision 결과 없음'}</strong>
            {extraction && <><p>수동 원본 확인 필요: {extraction.manualVerificationRequired ? '예' : '아니요'}</p>{extraction.failureReason && <p>처리 사유: {extraction.failureReason}</p>}{extraction.extracted && <pre>{JSON.stringify(extraction.extracted, null, 2)}</pre>}</>}
          </div>}
        </li>
      })}</ul>}
      {ocrError && <p role="alert">OCR 상태 조회 오류: {ocrError}</p>}
      <p role="note">OCR 추출값은 검수된 문서의 참고 자료입니다. 추출 성공 여부와 관계없이 직원이 원본과 증빙을 확인해야 하며, OCR 결과만으로 접수나 상속 권리를 승인하지 않습니다.</p>
      {review && <section><h3>현재 revision 검토 결과 {review.revision}</h3><p>Agent: {review.agentRunStatus} · 상태: {review.reviewStatus ?? '미실행'}</p><ul>{review.reviewItems.map((entry, index) => <li key={`${entry.category ?? 'item'}-${index}`}>{entry.category}: {entry.message} ({entry.source})</li>)}</ul></section>}
      <h3>접수 제출</h3>
      <label><input type="checkbox" checked={consented} onChange={event => setConsented(event.target.checked)} /> 접수에 필요한 안내를 확인하고 동의합니다.</label>
      <label><input type="checkbox" checked={signatureComplete} onChange={event => setSignatureComplete(event.target.checked)} /> 화면에서 모의 서명 확인을 완료했습니다. (획·이미지는 전송하지 않음)</label>
      <button type="button" disabled={!taskBffEnabled || busy || !consented || !signatureComplete} onClick={() => void saveConsentAndSignature()}>동의·모의 서명 상태 저장</button>
      {!c02MockSmsEnabled && <p role="status">OTP/제출 버튼은 비활성 상태입니다. `VITE_C02_SMS_MODE=mock`와 백엔드 테스트용 Mock SMS 설정이 함께 확인된 환경에서만 켤 수 있습니다.</p>}
      {c02MockSmsEnabled && <>
        <button type="button" disabled={!taskBffEnabled || busy || !consented || !signatureComplete} onClick={() => void issueOtp()}>테스트용 OTP 발급</button>
        {challenge && <><p>인증 대상 {challenge.maskedPhone} · 만료 {challenge.expiresAt}</p><label>OTP <input value={otp} onChange={event => setOtp(event.target.value)} /></label><button type="button" disabled={busy || !otp} onClick={() => void verifyOtp()}>OTP 검증</button></>}
        {bindingId && <button type="button" disabled={busy || !latestRequiredUploaded} onClick={() => void submit()}>Case 제출 및 사전검토 요청</button>}
      </>}
    </>}
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    <p><Link to="/tasks/c02/notifications">고객 알림 확인</Link></p>
  </section>
}
