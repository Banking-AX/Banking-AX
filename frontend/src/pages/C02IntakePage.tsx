import { useMemo, useState } from 'react'
type Heir = { personRef: string; name: string; relationship: string; visitStatus: 'ATTENDING'|'ABSENT'|'UNCONFIRMED_ABSENT'; delegationMethod: 'NONE'|'DOCUMENT'|'RECEIPT' }
const emptyHeir = (): Heir => ({ personRef: crypto.randomUUID(), name: '', relationship: '', visitStatus: 'ATTENDING', delegationMethod: 'NONE' })
export function C02IntakePage() {
  const [deathDate, setDeathDate] = useState(''); const [name, setName] = useState(''); const [heirs, setHeirs] = useState<Heir[]>([emptyHeir()]); const [confirmed, setConfirmed] = useState(false)
  const outOfScope = deathDate !== '' && deathDate < '2008-01-01'
  const documents = useMemo(() => ['Decedent family certificate', 'Decedent basic certificate', ...heirs.flatMap(h => {
    if (h.visitStatus === 'ATTENDING') return [`${h.name || 'Heir'} ID`, `${h.name || 'Heir'} seal/signature (visit only)`]
    if (h.delegationMethod === 'DOCUMENT') return [`${h.name || 'Absent heir'} ID copy`, `${h.name || 'Absent heir'} seal certificate`, `${h.name || 'Absent heir'} power of attorney`]
    if (h.delegationMethod === 'RECEIPT') return [`${h.name || 'Absent heir'} delegation receipt number`]
    return []
  })], [heirs])
  const update = (index: number, patch: Partial<Heir>) => setHeirs(items => items.map((item, i) => i === index ? {...item, ...patch} : item))
  return <section><h1>C-02 Inheritance Deposit Pre-intake</h1><p>This is a pre-visit intake. Staff confirms entitlement and documents.</p>
    <label>Decedent name <input value={name} onChange={e=>setName(e.target.value)} /></label><label>Death date <input type="date" value={deathDate} onChange={e=>setDeathDate(e.target.value)} /></label>
    {outOfScope && <p role="alert">Deaths before 2008-01-01 are outside this PoC intake scope.</p>}
    <h2>Heirs</h2>{heirs.map((h,i)=><fieldset key={h.personRef}><input aria-label={`Heir ${i+1} name`} placeholder="Name" value={h.name} onChange={e=>update(i,{name:e.target.value})}/><input placeholder="Relationship" value={h.relationship} onChange={e=>update(i,{relationship:e.target.value})}/><select value={h.visitStatus} onChange={e=>update(i,{visitStatus:e.target.value as Heir['visitStatus']})}><option value="ATTENDING">Attending</option><option value="ABSENT">Absent</option><option value="UNCONFIRMED_ABSENT">Temporarily absent / unconfirmed</option></select>{h.visitStatus !== 'ATTENDING' && <select value={h.delegationMethod} onChange={e=>update(i,{delegationMethod:e.target.value as Heir['delegationMethod']})}><option value="NONE">Unconfirmed</option><option value="DOCUMENT">Document delegation</option><option value="RECEIPT">SuperSOL receipt</option></select>}<button type="button" onClick={()=>setHeirs(xs=>xs.filter((_,n)=>n!==i))}>Remove</button></fieldset>)}<button type="button" onClick={()=>setHeirs(xs=>[...xs,emptyHeir()])}>Add heir</button>
    <h2>Required documents</h2><ul>{documents.map(d=><li key={d}>{d} — upload state must be checked before submission</li>)}</ul><label><input type="checkbox" checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/> I confirmed the entered information.</label><button type="button" disabled={!confirmed || outOfScope}>Continue to signature and SMS</button><p>Until server verification is connected, this does not submit a case.</p></section>
}
