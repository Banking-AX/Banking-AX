import { useState } from 'react'
import { useParams } from 'react-router-dom'

type Heir = { personRef: string; name: string; birthDate: string; relationship: string; applicant: boolean; visitStatus: 'ATTENDING'|'ABSENT'|'UNCONFIRMED_ABSENT'; delegationMethod: 'NONE'|'DOCUMENT'|'RECEIPT'; delegationReceiptNo: string }
type PrepareData = { requiredDocuments?: { documentTypeCode: string; personRef?: string; requirement: string; note: string }[]; submission?: { submittable: boolean; errors: string[]; outOfScope: string[]; missingDocuments: string[] } }
const emptyHeir = (applicant = false): Heir => ({ personRef: crypto.randomUUID(), name: '', birthDate: '', relationship: '', applicant, visitStatus: 'ATTENDING', delegationMethod: 'NONE', delegationReceiptNo: '' })

export function C02IntakePage() {
  const { workItemId } = useParams(); const [deathDate, setDeathDate] = useState(''); const [name, setName] = useState(''); const [heirs, setHeirs] = useState<Heir[]>([emptyHeir(true)]); const [confirmed, setConfirmed] = useState(false); const [result, setResult] = useState<PrepareData | null>(null); const [error, setError] = useState<string | null>(null); const [busy, setBusy] = useState(false)
  const update = (index: number, patch: Partial<Heir>) => setHeirs(items => items.map((item, i) => i === index ? {...item, ...patch} : item))
  const validate = async () => {
    if (!workItemId) return setError('업무 항목 식별자가 없습니다.')
    setBusy(true); setError(null)
    try {
      const response = await fetch(`${import.meta.env.VITE_AI_BASE_URL ?? ''}/api/v1/customer-agent/execute`, { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({ taskCode: 'INHERITANCE_DEPOSIT', requestId: `c02-ui-${workItemId}`, workItemId: Number(workItemId), customerId: 1, decedent: {name, deathDate, depositTotal: 0}, heirs: heirs.map(h => ({...h, birthDate: h.birthDate || null, higherOrderOrSubrogation: false, delegationReceiptNo: h.delegationReceiptNo || null})), documentStatuses: {}, submissionConfirmed: confirmed }) })
      const payload = await response.json()
      if (!response.ok) throw new Error(payload.detail ?? payload.error?.message ?? '사전 검증에 실패했습니다.')
      setResult(payload.validation?.data ?? null)
    } catch (e) { setError(e instanceof Error ? e.message : '사전 검증에 실패했습니다.') } finally { setBusy(false) }
  }
  const submission = result?.submission
  return <section><h1>C-02 Inheritance Deposit Pre-intake</h1><p>방문 전 준비 확인용이며, 직원이 권리·원본·최종 결과를 확인합니다.</p>
    <label>Decedent name <input value={name} onChange={e=>setName(e.target.value)} /></label><label>Death date <input type="date" value={deathDate} onChange={e=>setDeathDate(e.target.value)} /></label>
    <h2>Heirs</h2>{heirs.map((h,i)=><fieldset key={h.personRef}><input aria-label={`Heir ${i+1} name`} placeholder="Name" value={h.name} onChange={e=>update(i,{name:e.target.value})}/><input placeholder="Relationship" value={h.relationship} onChange={e=>update(i,{relationship:e.target.value})}/><input type="date" value={h.birthDate} onChange={e=>update(i,{birthDate:e.target.value})}/><label><input type="radio" checked={h.applicant} onChange={()=>setHeirs(xs=>xs.map((x,n)=>({...x,applicant:n===i})))} /> Applicant</label><select value={h.visitStatus} onChange={e=>update(i,{visitStatus:e.target.value as Heir['visitStatus']})}><option value="ATTENDING">Attending</option><option value="ABSENT">Absent</option><option value="UNCONFIRMED_ABSENT">Temporarily absent / unconfirmed</option></select>{h.visitStatus !== 'ATTENDING' && <><select value={h.delegationMethod} onChange={e=>update(i,{delegationMethod:e.target.value as Heir['delegationMethod']})}><option value="NONE">Unconfirmed</option><option value="DOCUMENT">Document delegation</option><option value="RECEIPT">SuperSOL receipt</option></select>{h.delegationMethod === 'RECEIPT' && <input placeholder="SuperSOL receipt number" value={h.delegationReceiptNo} onChange={e=>update(i,{delegationReceiptNo:e.target.value})}/>}</>}<button type="button" onClick={()=>setHeirs(xs=>xs.filter((_,n)=>n!==i))}>Remove</button></fieldset>)}<button type="button" onClick={()=>setHeirs(xs=>[...xs,emptyHeir()])}>Add heir</button>
    <label><input type="checkbox" checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/> I confirmed the entered information.</label><button type="button" disabled={!confirmed || busy} onClick={validate}>{busy ? 'Checking…' : 'Check required documents'}</button>
    {error && <p role="alert">{error}</p>}{submission && <><h2>Spring validation result</h2>{submission.outOfScope.length > 0 && <p role="alert">Out of scope: {submission.outOfScope.join(', ')}</p>}{submission.errors.length > 0 && <p role="alert">Required information: {submission.errors.join(', ')}</p>}<p>{submission.submittable ? '서류 상태 확인 후 모의 서명·SMS 단계가 필요합니다. 아직 접수 완료가 아닙니다.' : '필수 서류 또는 입력 보완이 필요합니다.'}</p><h2>Required documents</h2><ul>{result?.requiredDocuments?.map(d=><li key={`${d.documentTypeCode}-${d.personRef ?? 'common'}`}>{d.documentTypeCode} ({d.requirement}) — {d.note}</li>)}</ul></>}
    <p>이 화면은 Spring 검증 결과를 표시할 뿐, 서버 인증·SMS 제출을 우회하거나 접수 완료를 표시하지 않습니다.</p></section>
}
