import { useState, type FormEvent } from 'react'
import { toUserMessageOr } from '../../api/errorMessages'
import { c02Api, type C02PolicyAnswer } from '../../api/taskApi'

const STATE_LABEL: Record<C02PolicyAnswer['status'], string> = { ANSWERED: '근거 기반 답변', INSUFFICIENT: '근거 부족 · 직접 확인', EVIDENCE_ONLY: '근거만 표시' }

function origin(answer: C02PolicyAnswer) {
  if (answer.method === 'LLM') return `AI 작성 (${answer.model ?? '모델 미상'})`
  return answer.method === 'NO_EVIDENCE' ? '검색 결과 없음 · AI 미사용' : 'AI 미사용 · 검색 근거만 표시'
}

/** STAFF-19 / RAG-10: asks the approved rule index. Nothing about this Case is sent, and the question is not stored. */
export function C02PolicyQuestion({ caseId }: { caseId: number }) {
  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState<C02PolicyAnswer | null>(null)
  const [asked, setAsked] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const text = question.trim()
  const ask = async (event: FormEvent) => {
    event.preventDefault()
    if (text.length < 2 || busy) return
    setBusy(true); setError(null)
    try {
      const response = await c02Api.askPolicyQuestion(caseId, { question: text, requestId: `c02-policy-question-${caseId}-${crypto.randomUUID()}` })
      setAnswer(response.data ?? null); setAsked(text); setQuestion('')
    } catch (cause) { setError(toUserMessageOr(cause, '답을 가져오지 못했어요. 주민번호·계좌번호 같은 번호를 뺐는지 확인하고 다시 시도해 주세요.')) }
    finally { setBusy(false) }
  }
  return <div className="v17-sd-ai__block v17-pq">
    <strong>업무기준 질문</strong>
    <form onSubmit={ask}>
      <label className="v17-pq__label" htmlFor="v17-pq-input">등록된 공식 업무기준에서만 찾아 답해요</label>
      <textarea id="v17-pq-input" rows={2} maxLength={300} value={question} onChange={event => setQuestion(event.target.value)} placeholder="예: 불참 상속인의 인감증명서는 언제 발급분까지 인정되나요?" />
      <span className="v17-sd-ai__muted">고객 이름·주민번호·계좌번호는 적지 마세요. 질문 내용은 저장하지 않아요.</span>
      <button type="submit" className="v17-btn v17-btn--line" disabled={busy || text.length < 2}>{busy ? '찾는 중…' : '질문하기'}</button>
    </form>
    {error && <p className="v17-alert" role="alert">{error}</p>}
    {answer && <div className={`v17-pq__answer is-${answer.status.toLowerCase()}`} aria-live="polite">
      <span className="v17-pq__asked">{asked}</span>
      <span className="v17-pq__state">{STATE_LABEL[answer.status]}</span>
      <p>{answer.answer}</p>
      {answer.citations.length > 0 && <ul>{answer.citations.map(row => <li key={row.docId + row.section}><b>{row.title} – {row.section}</b><span>{row.excerpt}</span></li>)}</ul>}
      <span className="v17-sd-ai__muted">{origin(answer)} · 참고용이며 이 Case의 판단이 아니에요.</span>
    </div>}
  </div>
}
