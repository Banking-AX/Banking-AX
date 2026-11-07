import { useEffect, useState } from 'react'
import { toUserMessageOr } from '../../api/errorMessages'
import { c02Api, type C02AiReview as Brief } from '../../api/taskApi'

export const aiReviewOrigin = (brief: Brief) => brief.method === 'LLM' ? `AI 작성 (${brief.model ?? '모델 미상'})` : 'AI 미사용 · 규칙 기반 정리'

const requestId = (action: string, caseId: number) => `c02-ai-review-${action}-${caseId}-${crypto.randomUUID()}`

/** Same colour, same meaning: green is what the system compared, amber is what a person still has to do. */
function Lines({ title, lines, tone, empty }: { title: string; lines: string[]; tone: 'matched' | 'todo'; empty: string }) {
  return <div className={`c02-ai-group c02-ai-group--${lines.length === 0 ? 'empty' : tone}`}><h3 className="c02-staff-subhead">{title}<small>{lines.length}건</small></h3>
    {lines.length === 0 ? <p className="c02-staff-muted">{empty}</p> : <ul className="c02-staff-plain-list">{lines.map(line => <li key={line}>{line}</li>)}</ul>}</div>
}

/** Reference only. Applying the draft fills the staff message; saving a decision stays a separate staff action. */
export function C02AiReviewCard({ caseId, revision, canGenerate, onApply }: { caseId: number; revision: number; canGenerate: boolean; onApply: ((draft: string) => void) | null }) {
  const [brief, setBrief] = useState<Brief | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    c02Api.aiReview(caseId, requestId('read', caseId)).then(response => { if (active) setBrief(response.data ?? null) }).catch(() => { if (active) setBrief(null) })
    return () => { active = false }
  }, [caseId, revision])

  const generate = async () => {
    setBusy(true); setError(null)
    try {
      const result = (await c02Api.generateAiReview(caseId, { revision, requestId: requestId('generate', caseId) })).data
      if (result?.status === 'FAILED') setError('AI 의견을 만들지 못했어요. 아래 자동 사전점검 결과로 검토를 계속할 수 있어요.')
      else setBrief(result ?? null)
    } catch (cause) { setError(toUserMessageOr(cause, 'AI 의견을 만들지 못했어요. 아래 자동 사전점검 결과로 검토를 계속할 수 있어요.')) } finally { setBusy(false) }
  }

  return <section className="c02-staff-card c02-staff-card--wide"><div className="c02-staff-card-head"><div><span className="c02-staff-kicker">직원 참고용</span><h2>AI 사전검토 의견</h2></div>{canGenerate && <button type="button" className="c02-staff-button-secondary" disabled={busy} onClick={() => void generate()}>{busy ? '의견 생성 중…' : brief ? '의견 다시 생성' : 'AI 의견 생성'}</button>}</div>
    {error && <p className="c02-staff-banner c02-staff-banner--error" role="alert">{error}</p>}
    {!brief && <p className="c02-staff-empty">아직 생성한 의견이 없어요.</p>}
    {brief && <>
      <p className="c02-ai-origin"><span className="c02-staff-badge c02-staff-badge--progress">{aiReviewOrigin(brief)}</span><span>r{brief.revision} 기준 · 참고 의견이며 최종 판단이 아니에요. 승인된 정책 공지와 다르면 공지를 먼저 확인해 주세요.</span></p>
      <h3 className="c02-staff-subhead">현재 접수 요약</h3><p className="c02-ai-summary">{brief.summary}</p>
      <div className="c02-ai-groups">
        <Lines title="확인된 사항" lines={brief.confirmed} tone="matched" empty="자동으로 일치한 항목이 없어요." />
        <Lines title="추가 확인사항" lines={brief.staffChecks} tone="todo" empty="AI가 추가로 짚은 항목이 없어요. 원본 확인은 그대로 필요해요." />
      </div>
      <h3 className="c02-staff-subhead">관련 업무기준 및 근거</h3>
      {brief.basisMissing ? <p className="c02-staff-empty">관련 업무기준 근거를 찾지 못했어요. 규정을 직접 확인해 주세요.</p> : <ul className="c02-staff-plain-list">{brief.basis.map(row => <li key={row.docId + row.reason}><code>{row.docId}</code> {row.reason}</li>)}</ul>}
      <div className="c02-ai-draft"><h3 className="c02-staff-subhead">고객 안내 초안</h3><p className="c02-staff-draft">{brief.customerDraft}</p>
        {onApply && <button type="button" className="c02-staff-button-secondary" onClick={() => onApply(brief.customerDraft)}>고객 안내에 적용</button>}</div>
    </>}
  </section>
}
