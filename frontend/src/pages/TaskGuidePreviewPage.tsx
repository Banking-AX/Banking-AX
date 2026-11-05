import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toUserMessageOr } from '../api/errorMessages'
import { taskGuideApi, type TaskGuide } from '../api/taskGuide'
import { TaskGuideCard, TaskGuideSteps } from '../features/taskGuide/TaskGuideCard'

const EXAMPLES = ['아버지가 돌아가셨는데 아버지 통장에 있는 돈은 어떻게 찾아요?', '청약통장 깨고 싶은데 손해 안 보려면 뭐 확인해야 돼요?', '대출 갚기가 너무 힘들어요', '이자부터 먼저 갚게 바꿀 수 있어요?']

/** DEV-only preview of the chat task-matching card until it is wired into the customer chat (T1). */
export function TaskGuidePreviewPage() {
  const navigate = useNavigate()
  const [input, setInput] = useState('')
  const [asked, setAsked] = useState<string | null>(null)
  const [guide, setGuide] = useState<TaskGuide | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ask = async (text: string) => {
    if (!text.trim()) return
    setAsked(text); setGuide(null); setError(null); setBusy(true)
    try { setGuide(await taskGuideApi.guide(text.trim())) } catch (cause) { setError(toUserMessageOr(cause, '안내를 만들지 못했어요. 잠시 후 다시 시도해 주세요.')) } finally { setBusy(false) }
  }
  return <main className="tg-preview">
    <h1>업무 매칭 카드 미리보기</h1>
    <p>고객이 애매하게 말해도 AI가 해당 은행 업무를 찾고, 검수된 은행 안내문으로 요약해요. (개발용 화면)</p>
    <div className="tg-examples">{EXAMPLES.map(example => <button type="button" key={example} disabled={busy} onClick={() => { setInput(example); void ask(example) }}>{example}</button>)}</div>
    <form className="tg-form" onSubmit={event => { event.preventDefault(); void ask(input) }}>
      <input value={input} onChange={event => setInput(event.target.value)} placeholder="예) 아버지 통장 돈 어떻게 찾아요?" aria-label="고객 메시지" />
      <button type="submit" disabled={busy || !input.trim()}>보내기</button>
    </form>
    {asked && <div className="tg-bubble">{asked}</div>}
    {busy && <TaskGuideSteps steps={[]} running />}
    {error && <p role="alert">{error}</p>}
    {guide && <TaskGuideCard guide={guide} onStart={code => { if (code === 'INHERITANCE_DEPOSIT') navigate('/tasks/c02'); else navigate('/chat') }} onOther={() => { setGuide(null); setAsked(null) }} />}
  </main>
}
