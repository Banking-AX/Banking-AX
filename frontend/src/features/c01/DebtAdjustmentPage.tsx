import { useState } from 'react'

/** T1 C-01 shell. Document upload remains an explicit T2 DocumentPort Fake boundary in G1. */
export function DebtAdjustmentPage() {
  const [consented, setConsented] = useState(false)
  const [signed, setSigned] = useState(false)
  const [status, setStatus] = useState('작성 중')
  return <section>
    <h1>채무조정 접수</h1>
    <p>대상 대출 선택 → 신청 정보 → 동의·모의 서명·SMS 인증 → 명시적 제출 순서로 진행합니다.</p>
    <label>대상 대출<select defaultValue="101"><option value="101">210-***-557731 (연체 대출)</option></select></label>
    <label>신청 사유<textarea placeholder="신청 사유를 입력하세요" /></label>
    <label>현재 상황<textarea placeholder="현재 상황을 입력하세요" /></label>
    <label>월 변제 가능 금액<input type="number" min="0" /></label>
    <div className="placeholder">필수서류 현황: DocumentPort Fake 연결 지점 — 실제 파일 업로드 완료로 표시하지 않습니다.</div>
    <label><input type="checkbox" checked={consented} onChange={e => setConsented(e.target.checked)} /> 필수 동의</label>
    <button type="button" onClick={() => setSigned(true)}>모의 서명 완료</button>
    <p>모의 서명 상태: {signed ? '완료 (획·이미지는 서버에 전송하지 않음)' : '미완료'}</p>
    <button type="button" disabled={!consented || !signed} onClick={() => setStatus('SMS 인증 대기')}>SMS 인증 요청</button>
    <button type="button" disabled={!consented || !signed} onClick={() => setStatus('제출 요청 준비됨')}>접수 제출</button>
    <p aria-live="polite">현재 상태: {status}. 최초 제출에서는 방문 예약을 만들지 않습니다.</p>
  </section>
}
