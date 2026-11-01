import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { c02Api, taskBffEnabled } from '../api/taskApi'

type Outcome = 'PROCEED' | 'REQUIREMENT_FAILED' | 'DOCUMENT_INSUFFICIENT'
type CaseData = { caseView?: { revision?: number; caseStatus?: string; reviewStatus?: string }; precheck?: { reviewItems?: { category?: string; message?: string; source?: string }[] }; documents?: { fileName?: string; status?: string }[] }

/** Staff actions are possible only behind a staff-session BFF; Agent output remains reference material. */
export function C02StaffReviewPage() {
  const { caseId } = useParams(); const parsedId = Number(caseId)
  const [data, setData] = useState<CaseData | null>(null); const [outcome, setOutcome] = useState<Outcome>('PROCEED'); const [message, setMessage] = useState(''); const [error, setError] = useState<string | null>(null); const [busy, setBusy] = useState(false)
  const load = async () => { if (!Number.isSafeInteger(parsedId) || parsedId <= 0) return setError('Case ID가 올바르지 않습니다.'); setBusy(true); setError(null); try { const r = await c02Api.staffCase(parsedId, `c02-staff-case-${parsedId}`); setData((r.data ?? null) as CaseData | null) } catch (e) { setError(e instanceof Error ? e.message : 'Case를 불러올 수 없습니다.') } finally { setBusy(false) } }
  const decide = async () => { const revision = data?.caseView?.revision; if (!Number.isInteger(revision) || !Number.isSafeInteger(parsedId)) return setError('최신 revision을 먼저 불러와야 합니다.'); const currentRevision = Number(revision); setBusy(true); setError(null); try { await c02Api.staffDecision(parsedId, { revision: currentRevision, requestId: `c02-staff-decision-${parsedId}-${currentRevision}`, outcome, customerMessage: message }); await load() } catch (e) { setError(e instanceof Error ? e.message : '최종결정을 저장할 수 없습니다.') } finally { setBusy(false) } }
  return <section><h1>C-02 직원 검토</h1><p>Case: {caseId ?? '미지정'}. Agent 사전검토와 OCR은 참고자료이며, 직원만 최종결정을 기록할 수 있습니다.</p>
    {!taskBffEnabled && <p role="status">직원 RBAC BFF 연결 대기 중입니다. 내부 gateway token은 이 화면에 없습니다.</p>}<button type="button" disabled={!taskBffEnabled || busy} onClick={load}>{busy ? '불러오는 중' : '최신 Case 불러오기'}</button>{error && <p role="alert">{error}</p>}
    {data && <><p>상태: {data.caseView?.caseStatus ?? '-'} / Agent 검토: {data.caseView?.reviewStatus ?? '-'}</p><h2>사전검토 항목</h2><ul>{data.precheck?.reviewItems?.map((item, i) => <li key={i}>{item.category}: {item.message} ({item.source})</li>)}</ul><h2>직원 최종결정</h2><select value={outcome} onChange={e => setOutcome(e.target.value as Outcome)}><option value="PROCEED">진행 가능</option><option value="REQUIREMENT_FAILED">요건 미충족</option><option value="DOCUMENT_INSUFFICIENT">서류 보완 필요</option></select><textarea value={message} onChange={e => setMessage(e.target.value)} placeholder="고객 안내 메시지"/><button type="button" disabled={!taskBffEnabled || busy} onClick={decide}>최종결정 및 고객 알림 저장</button></>}
    <p><Link to="/tasks/c02/notifications">고객 알림 화면</Link></p>
  </section>
}
