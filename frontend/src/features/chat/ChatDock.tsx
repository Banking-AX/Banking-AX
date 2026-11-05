import { useEffect, useRef } from 'react'
import { useChatDock } from './chatDockContext'

/**
 * Header chip and input lock come from this one value. The server sends no progress steps yet,
 * so "working" only means a customer message is waiting for the server's answer.
 */
export type DockStage = 'idle' | 'working'

export const DOCK_STAGES: Record<DockStage, { label: string; locksInput: boolean }> = {
  idle: { label: '대기', locksInput: false },
  working: { label: '업무 처리 중', locksInput: true },
}

export function DockStageChip({ stage }: { stage: DockStage }) {
  return <span className={`chat-dock__chip chat-dock__chip--${stage}`}>
    <span aria-hidden="true" className="chat-dock__chip-dot"></span>{DOCK_STAGES[stage].label}
  </span>
}

export const DOCK_ROBOT = <svg width="28" height="28" viewBox="0 0 104 104" fill="none" aria-hidden="true"><line x1="52" y1="14" x2="52" y2="26" stroke="#2E70C3" strokeWidth="6" strokeLinecap="round"></line><circle cx="52" cy="11" r="8" fill="#C9682A"></circle><rect x="16" y="26" width="72" height="58" rx="24" fill="#2E70C3"></rect><rect x="28" y="40" width="48" height="30" rx="14" fill="#EEF6FE"></rect><circle cx="42" cy="54" r="6" fill="#1E3A5F"></circle><circle cx="62" cy="54" r="6" fill="#1E3A5F"></circle></svg>

/**
 * Right-hand dock column of a customer page (docs/design-ref/Main.dc.html, AssistDock.dc.html).
 * Open: an empty slot the chat card is portaled into. Collapsed: a bar (1280px and wider) or a round button.
 */
export function ChatDockColumn() {
  const { started, collapsed, unseen, expand, setSlot, focusRequest, clearFocusRequest } = useChatDock()
  const railButtonRef = useRef<HTMLButtonElement>(null)
  const roundButtonRef = useRef<HTMLButtonElement>(null)

  // After [도크 접기], focus the expand control that is visible at this width.
  useEffect(() => {
    if (!collapsed || focusRequest !== 'expandButton') return
    const visible = [railButtonRef.current, roundButtonRef.current].find(button => button && button.getClientRects().length > 0)
    visible?.focus()
    clearFocusRequest()
  }, [collapsed, focusRequest, clearFocusRequest])

  if (!started) return null

  if (collapsed) {
    const label = unseen > 0 ? `AI 도우미 · 새 안내 ${unseen}건` : 'AI 도우미'
    return <>
      <div className="chat-dock-rail-col">
        <div className="chat-dock-rail">
          <button ref={railButtonRef} type="button" aria-label="도크 펼치기" onClick={expand} className="chat-dock-rail__expand">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M15 6l-6 6 6 6"></path></svg>
          </button>
          <span className="chat-dock-rail__avatar">
            {DOCK_ROBOT}
            {unseen > 0 && <span aria-hidden="true" className="chat-dock-badge chat-dock-badge--rail">{unseen}</span>}
          </span>
          <span className="chat-dock-rail__label">{label}</span>
        </div>
      </div>
      <button ref={roundButtonRef} type="button" aria-label={unseen > 0 ? `AI 도우미 열기, 새 안내 ${unseen}건` : 'AI 도우미 열기'} onClick={expand} className="chat-dock-round">
        <span aria-hidden="true" className="chat-dock-round__icon">{DOCK_ROBOT}</span>
        {unseen > 0 && <span aria-hidden="true" className="chat-dock-badge chat-dock-badge--round">{unseen}</span>}
      </button>
    </>
  }

  return <aside aria-label="AI 금융 도우미" className="chat-dock-col">
    <div ref={setSlot} className="chat-dock-slot"></div>
  </aside>
}
