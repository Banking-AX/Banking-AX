import { useEffect, useState } from 'react'
import type { TaskGuide, TaskGuideStep } from '../../api/taskGuide'
import './task-guide.css'

const STEP_REVEAL_MS = 450

/**
 * Chat card for "which bank task is this?". Steps are the ones the server actually ran; they are only
 * revealed one after another so the customer can follow what the Agent did.
 */
export function TaskGuideSteps({ steps, running }: { steps: TaskGuideStep[]; running: boolean }) {
  const [shown, setShown] = useState(0)
  useEffect(() => {
    setShown(0)
    if (steps.length === 0) return
    const timer = setInterval(() => setShown(count => { if (count >= steps.length) { clearInterval(timer); return count } return count + 1 }), STEP_REVEAL_MS)
    return () => clearInterval(timer)
  }, [steps])
  if (running) return <ol className="tg-steps" aria-live="polite"><li className="is-running"><span aria-hidden="true" />요청을 이해하고 업무를 찾고 있어요…</li></ol>
  return <ol className="tg-steps" aria-live="polite">{steps.slice(0, shown).map(step => <li key={step.key} className={step.status === 'DONE' ? 'is-done' : 'is-warn'}>
    <span aria-hidden="true">{step.status === 'DONE' ? '✓' : '!'}</span>{step.label}<em>{step.detail}</em>
  </li>)}</ol>
}

export function TaskGuideCard({ guide, onStart, onOther }: { guide: TaskGuide; onStart?: (taskCode: string) => void; onOther?: () => void }) {
  const [stepsDone, setStepsDone] = useState(guide.steps.length === 0)
  useEffect(() => {
    setStepsDone(guide.steps.length === 0)
    const timer = setTimeout(() => setStepsDone(true), guide.steps.length * STEP_REVEAL_MS + 150)
    return () => clearTimeout(timer)
  }, [guide])
  return <div className="tg-card">
    <TaskGuideSteps steps={guide.steps} running={false} />
    {stepsDone && (guide.matched && guide.taskCode ? <section className="tg-body" aria-label="업무 안내">
      {guide.understood && <div className="tg-row"><b>💬 이해한 내용</b><p>{guide.understood}</p></div>}
      <div className="tg-row"><b>🏦 해당 은행 업무</b><p className="tg-task"><strong>{guide.officialName}</strong>{guide.kind && <span>{guide.kind}</span>}</p></div>
      {guide.bullets.length > 0 && <div className="tg-row"><b>📄 은행 안내 요약</b>
        <ul>{guide.bullets.map((bullet, index) => { const cite = guide.citations[bullet.citation]; return <li key={index}>{bullet.text}{cite && <cite>{cite.title} – {cite.section}</cite>}</li> })}</ul>
        <small>{guide.summaryMethod === 'LLM' ? 'AI가 검수된 은행 안내문만 근거로 요약했어요. 숫자는 원문과 대조했어요.' : '검수된 은행 안내문에서 그대로 옮겼어요.'}</small>
      </div>}
      {guide.nextStep && <div className="tg-row"><b>➡️ 다음 진행</b><p>{guide.nextStep}</p></div>}
      <div className="tg-actions">
        {onStart && <button type="button" className="tg-primary" onClick={() => onStart(guide.taskCode!)}>이 업무로 시작</button>}
        {onOther && <button type="button" className="tg-secondary" onClick={onOther}>다른 업무예요</button>}
      </div>
      <p className="tg-note">안내는 참고용이며, 실제 처리 가능 여부는 은행 규칙과 직원 확인으로 정해져요.</p>
    </section> : <p className="tg-clarify">{guide.clarification ?? '어떤 업무인지 조금 더 알려주세요.'}</p>)}
  </div>
}
