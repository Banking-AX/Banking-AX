import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { c02Api, taskBffEnabled, type C02Heir } from '../api/taskApi'

type RequiredDocument = { documentTypeCode: string; personRef?: string; requirement: string; note: string }
type PrepareData = { requiredDocuments?: RequiredDocument[]; submission?: { submittable: boolean; errors: string[]; outOfScope: string[]; missingDocuments: string[] }; caseView?: { caseId: number; revision: number } }
type HeirForm = Omit<C02Heir, 'birthDate' | 'delegationReceiptNo' | 'higherOrderOrSubrogation'> & { birthDate: string; delegationReceiptNo: string }
const emptyHeir = (applicant = false): HeirForm => ({ personRef: crypto.randomUUID(), name: '', birthDate: '', relationship: '', applicant, visitStatus: 'ATTENDING', delegationMethod: 'NONE', delegationReceiptNo: '' })

/** C-02 preparation sends no customer identity from the browser; the BFF derives it from its session. */
export function C02IntakePage() {
  const { workItemId } = useParams()
  const [deathDate, setDeathDate] = useState(''); const [name, setName] = useState('')
  const [heirs, setHeirs] = useState<HeirForm[]>([emptyHeir(true)])
  const [result, setResult] = useState<PrepareData | null>(null); const [error, setError] = useState<string | null>(null); const [busy, setBusy] = useState(false)
  const update = (index: number, patch: Partial<HeirForm>) => setHeirs(items => items.map((item, i) => i === index ? { ...item, ...patch } : item))
  const validate = async () => {
    const item = Number(workItemId)
    if (!Number.isSafeInteger(item) || item <= 0 || !name || !deathDate) { setError('업무 ID와 피상속인 정보를 확인해 주세요.'); return }
    setBusy(true); setError(null)
    try {
      const response = await c02Api.prepare({ requestId: `c02-ui-${item}`, workItemId: item, registeredPhone: null, decedent: { name, birthDate: null, deathDate, depositTotal: 0 }, heirs: heirs.map(h => ({ ...h, birthDate: h.birthDate || null, delegationReceiptNo: h.delegationReceiptNo || null, higherOrderOrSubrogation: false })), documentStatuses: {} })
      setResult((response.data ?? null) as PrepareData | null)
    } catch (e) { setError(e instanceof Error ? e.message : '사전 검증에 실패했습니다.') } finally { setBusy(false) }
  }
  const submission = result?.submission
  return <section><h1>C-02 상속예금 사전접수</h1><p>직원은 원본·권리관계를 직접 확인하며, 사전검토 결과는 자동 승인하지 않습니다.</p>
    {!taskBffEnabled && <p role="status">로그인 BFF 연결 대기 중입니다. 세션 없이 Case를 만들거나 고객 ID를 임의로 전송하지 않습니다.</p>}
    <label>피상속인 성명 <input value={name} onChange={e => setName(e.target.value)} /></label><label>사망일 <input type="date" value={deathDate} onChange={e => setDeathDate(e.target.value)} /></label>
    <h2>상속인</h2>{heirs.map((h, i) => <fieldset key={h.personRef}><input aria-label={`상속인 ${i + 1} 성명`} placeholder="성명" value={h.name} onChange={e => update(i, { name: e.target.value })}/><input placeholder="관계" value={h.relationship} onChange={e => update(i, { relationship: e.target.value })}/><input type="date" value={h.birthDate} onChange={e => update(i, { birthDate: e.target.value })}/><label><input type="radio" checked={h.applicant} onChange={() => setHeirs(xs => xs.map((x, n) => ({ ...x, applicant: n === i })))} /> 신청인</label><select value={h.visitStatus} onChange={e => update(i, { visitStatus: e.target.value as HeirForm['visitStatus'] })}><option value="ATTENDING">방문</option><option value="ABSENT">불참</option><option value="UNCONFIRMED_ABSENT">잠정 불참</option></select><button type="button" onClick={() => setHeirs(xs => xs.filter((_, n) => n !== i))}>삭제</button></fieldset>)}<button type="button" onClick={() => setHeirs(xs => [...xs, emptyHeir()])}>상속인 추가</button>
    <button type="button" disabled={!taskBffEnabled || busy} onClick={validate}>{busy ? '확인 중' : '필수 서류 확인'}</button>
    {error && <p role="alert">{error}</p>}{submission && <><h2>Spring 검증 결과</h2>{submission.outOfScope.length > 0 && <p role="alert">범위 제외: {submission.outOfScope.join(', ')}</p>}{submission.errors.length > 0 && <p role="alert">입력 보완: {submission.errors.join(', ')}</p>}<p>{submission.submittable ? '다음 단계에서 동의·모의서명·SMS 인증 및 서류 업로드가 필요합니다.' : '보완이 필요한 입력 또는 서류가 있습니다.'}</p><h2>필수 서류</h2><ul>{result?.requiredDocuments?.map(d => <li key={`${d.documentTypeCode}-${d.personRef ?? 'common'}`}>{d.documentTypeCode} ({d.requirement}) — {d.note}</li>)}</ul></>}
  </section>
}
