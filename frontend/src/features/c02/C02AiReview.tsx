import { useEffect, useState } from 'react'
import { toUserMessageOr } from '../../api/errorMessages'
import { c02Api, type C02AiReview as Brief } from '../../api/taskApi'
import { C02PolicyQuestion } from './C02PolicyQuestion'

export const aiReviewOrigin = (brief: Brief) => brief.method === 'LLM' ? `AI 작성 (${brief.model ?? '모델 미상'})` : 'AI 미사용 · 규칙 기반 정리'

const requestId = (action: string, caseId: number) => `c02-ai-review-${action}-${caseId}-${crypto.randomUUID()}`

export function AiTag() {
  return <span className="v17-tag v17-tag--ai"><svg width="10" height="10" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3l2.2 6.8L21 12l-6.8 2.2L12 21l-2.2-6.8L3 12l6.8-2.2z" fill="currentColor" /></svg>AI</span>
}

export type AiCandidate = { status: string; tone: 'danger' | 'warn'; title: string; body: string }
export type AiStep = { who: '규칙' | 'AI' | '직원'; text: string; state: string; tone: 'done' | 'doing' | 'wait' }
export type AiEvidence = { id: number; name: string }

/** Loads the stored opinion and asks for a new one. Reference only: nothing here decides a Case or reaches the customer. */
function useBrief(caseId: number, revision: number) {
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
      if (result?.status === 'FAILED') setError('AI 의견을 만들지 못했어요. 자동 사전점검 결과로 검토를 계속할 수 있어요.')
      else setBrief(result ?? null)
    } catch (cause) { setError(toUserMessageOr(cause, 'AI 의견을 만들지 못했어요. 자동 사전점검 결과로 검토를 계속할 수 있어요.')) } finally { setBusy(false) }
  }
  return { brief, busy, error, generate }
}

function GenerateButton({ brief, busy, onClick }: { brief: Brief | null; busy: boolean; onClick: () => void }) {
  return <button type="button" className="v17-ai-generate" disabled={busy} onClick={onClick}>{busy ? '의견 생성 중…' : brief ? '의견 다시 생성' : 'AI 의견 생성'}</button>
}

type PanelProps = {
  caseId: number; revision: number; canGenerate: boolean; reviewStarted: boolean
  counts: { ok: number; un: number; need: number }; candidates: AiCandidate[]; steps: AiStep[]; evidence: AiEvidence[]
  onEvidence: (documentFileId: number) => void
}

/** StaffDetail right column: summary, tallies, candidates, the staff checklist, processing steps and the files behind them. */
export function C02AiReviewPanel({ caseId, revision, canGenerate, reviewStarted, counts, candidates, steps, evidence, onEvidence }: PanelProps) {
  const { brief, busy, error, generate } = useBrief(caseId, revision)
  const [open, setOpen] = useState(true)
  const [ticked, setTicked] = useState<Record<string, boolean>>({})
  const checks = brief?.staffChecks ?? []
  const tickedN = checks.filter(line => ticked[line]).length
  return <aside className="v17-sd-ai" aria-labelledby="v17-sd-ai-title">
    <div className="v17-sd-ai__head"><AiTag /><h2 id="v17-sd-ai-title">AI 검토 보조</h2><button type="button" onClick={() => setOpen(value => !value)}>{open ? '접기' : '펼치기'}</button></div>
    {open && <div className="v17-sd-ai__body">
      <p className="v17-sd-ai__note">참고용 보조예요. 진행 가능·불가와 결과 등록은 직원이 정해요.</p>
      {error && <p className="v17-alert" role="alert">{error}</p>}
      <div className="v17-sd-ai__block"><strong>요약</strong>
        {brief ? <><span className="v17-sd-ai__summary">{brief.summary}</span><span className="v17-sd-ai__origin">{aiReviewOrigin(brief)} · r{brief.revision} 기준 · 승인된 정책 공지와 다르면 공지를 먼저 확인해 주세요.</span></>
          : <span className="v17-sd-ai__muted">아직 생성한 의견이 없어요.</span>}
        {canGenerate && <GenerateButton brief={brief} busy={busy} onClick={() => void generate()} />}
      </div>
      <div className="v17-sd-ai__tally">
        <div className="is-ok"><strong>{counts.ok}</strong><span>확인함</span></div>
        <div className="is-un"><strong>{counts.un}</strong><span>못 확인함</span></div>
        <div className="is-need"><strong>{counts.need}</strong><span>원문 필요</span></div>
      </div>
      <div className="v17-sd-ai__block"><strong>불일치 후보</strong>
        {candidates.length === 0 ? <span className="v17-sd-ai__muted">자동 대조에서 찾은 불일치 후보가 없어요.</span>
          : candidates.map(item => <div className="v17-sd-ai__cand" key={item.title + item.body}><span><span className={`v17-pill v17-pill--${item.tone}`}>{item.status}</span><strong>{item.title}</strong></span><span>{item.body}</span></div>)}
      </div>
      {brief && brief.confirmed.length > 0 && <div className="v17-sd-ai__block"><strong>확인된 사항</strong><ul className="v17-sd-ai__list">{brief.confirmed.map(line => <li key={line}>{line}</li>)}</ul></div>}
      <div className="v17-sd-ai__block">
        <div className="v17-sd-ai__row"><span className="v17-tag v17-tag--staff">직원</span><strong>직원 확인 {tickedN} / {checks.length}</strong></div>
        {!reviewStarted && checks.length > 0 && <span className="v17-sd-ai__warn">검토를 시작하면 체크할 수 있어요.</span>}
        {checks.length === 0 && <span className="v17-sd-ai__muted">AI 의견을 만들면 직원이 확인할 항목이 여기에 표시돼요.</span>}
        {checks.map(line => <button type="button" key={line} className={`v17-sd-ai__check${ticked[line] ? ' is-on' : ''}`} disabled={!reviewStarted} aria-pressed={Boolean(ticked[line])}
          onClick={() => setTicked(current => ({ ...current, [line]: !current[line] }))}><span aria-hidden="true">{ticked[line] ? '✓' : ''}</span><span>{line}</span></button>)}
        {checks.length > 0 && <span className="v17-sd-ai__muted">체크는 이 화면에서만 표시되고 저장되지 않아요.</span>}
      </div>
      <div className="v17-sd-ai__block"><strong>처리 단계</strong>
        <ol className="v17-sd-ai__steps">{steps.map(step => <li key={step.text}><span className={`v17-who v17-who--${step.who === '규칙' ? 'rule' : step.who === 'AI' ? 'ai' : 'staff'}`}>{step.who}</span><span>{step.text}</span><span className={`v17-state v17-state--${step.tone}`}>{step.state}</span></li>)}</ol>
      </div>
      <div className="v17-sd-ai__block"><strong>근거 서류</strong>
        <div className="v17-sd-ai__files">{evidence.map(file => <button type="button" key={file.id} onClick={() => onEvidence(file.id)}>{file.name}</button>)}</div>
        <span className="v17-sd-ai__muted">누르면 서류 확인 화면에서 원문을 열 수 있어요.</span>
      </div>
      <C02PolicyQuestion caseId={caseId} />
      {brief && <div className="v17-sd-ai__block"><strong>관련 업무기준 및 근거</strong>
        {brief.basisMissing ? <span className="v17-sd-ai__muted">관련 업무기준 근거를 찾지 못했어요. 규정을 직접 확인해 주세요.</span>
          : <ul className="v17-sd-ai__list">{brief.basis.map(row => <li key={row.docId + row.reason}><code>{row.docId}</code> {row.reason}</li>)}</ul>}
      </div>}
    </div>}
  </aside>
}

/** StaffFinal "AI 초안": the opinion for the internal record and the customer draft. Applying only fills the staff's message box. */
export function C02AiDraftCard({ caseId, revision, canGenerate, outcomeLabel, composedDraft, onApply }: { caseId: number; revision: number; canGenerate: boolean; outcomeLabel: string | null; composedDraft: string | null; onApply: ((draft: string) => void) | null }) {
  const { brief, busy, error, generate } = useBrief(caseId, revision)
  // Documents and reasons come from the saved checks, so the model can neither drop one nor reword a reason.
  const customerDraft = composedDraft ?? brief?.customerDraft ?? null
  const draftNote = outcomeLabel ? `‘${outcomeLabel}’ 결과와 직원이 저장한 서류 확인 결과로 채웠어요 · 고쳐서 등록해 주세요`
    : composedDraft ? '직원이 저장한 서류 확인 결과로 채웠어요 · 검토 결과를 고르면 그 결과에 맞는 안내로 바뀌어요' : 'AI가 결과를 고르지 않아요 · 결과에 맞게 고쳐 주세요'
  return <section className="v17-sf-ai">
    <div className="v17-sf-ai__head"><AiTag /><h2>AI 초안 · 직원이 고쳐서 등록</h2><span>참고용 · 결과를 정하거나 발송하지 않아요</span>{canGenerate && <GenerateButton brief={brief} busy={busy} onClick={() => void generate()} />}</div>
    {error && <p className="v17-alert" role="alert">{error}</p>}
    {!brief && <p className="v17-sf-ai__empty">아직 생성한 의견이 없어요.</p>}
    {brief && <div className="v17-sf-ai__block"><strong>직원 검토 의견 초안 <span>내부 기록용 · 고객에게 발송되지 않아요</span></strong>
      <p>{brief.summary}</p>
      {brief.basis.length > 0 && <span className="v17-sf-ai__src">근거: {brief.basis.map(row => `${row.docId} – ${row.reason}`).join(' · ')}</span>}
    </div>}
    {customerDraft && <div className="v17-sf-ai__block"><strong>고객 안내 초안 <span>{draftNote}</span></strong>
      <p className="is-pre">{customerDraft}</p>
      {onApply && (outcomeLabel || !composedDraft) && <button type="button" onClick={() => onApply(customerDraft)}>초안을 안내 문구란에 넣고 수정하기</button>}
    </div>}
    {brief && <span className="v17-sf-ai__origin">{composedDraft ? '직원 검토 의견: ' : ''}{aiReviewOrigin(brief)} · r{brief.revision} 기준</span>}
  </section>
}
