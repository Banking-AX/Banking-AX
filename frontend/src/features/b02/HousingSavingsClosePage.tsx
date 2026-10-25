import { useState } from 'react'
import { useParams } from 'react-router-dom'

type Decision = 'BASE_RATE'|'MANUAL_REVIEW'|'DOCUMENT_REQUIRED'|null
/** No browser-to-Spring call: the internal Gateway token must remain server-side. */
export function HousingSavingsClosePage() {
 const { workItemId }=useParams(); const [waive,setWaive]=useState(false); const [taxed,setTaxed]=useState<'unknown'|'none'|'exists'>('unknown'); const [decision,setDecision]=useState<Decision>(null); const [consent,setConsent]=useState(false); const [signed,setSigned]=useState(false); const [otp,setOtp]=useState(''); const [confirmed,setConfirmed]=useState(false)
 const invalidate=()=>{setDecision(null);setConfirmed(false)}
 const preview=()=>{ if(waive)setDecision('BASE_RATE'); else if(taxed==='exists')setDecision('MANUAL_REVIEW'); else if(taxed==='unknown')setDecision('DOCUMENT_REQUIRED'); else setDecision(null) }
 const blocked=decision==='MANUAL_REVIEW'||decision==='DOCUMENT_REQUIRED'
 return <section><h1>청년 주택드림 청약통장 Mock 해지</h1><p>업무 ID: {workItemId ?? '미확인'}. 실제 은행 계좌 해지·지급이 아닌 개발용 화면입니다.</p>
 <h2>대상 통장</h2><p>계좌·상품·상태는 인증된 서버 조회 경계가 준비된 후 표시됩니다. 잔액·이자·세금·지급금은 임의로 표시하지 않습니다.</p>
 <h2>사전검증 조건</h2><label><input type="checkbox" checked={waive} onChange={e=>{setWaive(e.target.checked);invalidate()}}/> 우대이율 포기</label><label>과세증명서 상태 <select value={taxed} onChange={e=>{setTaxed(e.target.value as typeof taxed);invalidate()}}><option value="unknown">미확인</option><option value="none">과세사실 없음</option><option value="exists">과세사실 있음</option></select></label><button type="button" onClick={preview}>사전검증 결과 표시</button>
 {decision==='BASE_RATE'&&<p role="status">B2-R3: 우대 포기 선택으로 기본금리 적용 안내입니다. 실제 실행은 서버 재검증 후에만 가능합니다.</p>}{decision==='MANUAL_REVIEW'&&<p role="alert">B2-R5: 과세사실이 있어 자동 해지할 수 없습니다. 영업점 상담이 필요합니다.</p>}{decision==='DOCUMENT_REQUIRED'&&<p role="alert">B2-R6: 과세증명서 확인 또는 우대 포기 선택이 필요합니다. 화면 확인만으로 우회할 수 없습니다.</p>}
 <h2>동의·인증·최종 확인</h2><label><input type="checkbox" checked={consent} onChange={e=>setConsent(e.target.checked)}/> 유의사항 확인 및 동의</label><button type="button" onClick={()=>setSigned(!signed)}>{signed?'모의서명 취소':'모의서명 완료 표시'}</button><label>OTP <input value={otp} onChange={e=>setOtp(e.target.value)} placeholder="서버 인증 경계 준비 후 사용" disabled/></label><label><input type="checkbox" checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/> Mock 해지 최종 확인</label><button type="button" disabled={!consent||!signed||!confirmed||blocked||!decision}>서버 인증 경계 준비 전 실행 불가</button><p>브라우저는 Gateway Token을 보유하지 않으므로 OTP 발급·검증 및 Mock Command 실행은 비활성화되어 있습니다.</p></section>
}
