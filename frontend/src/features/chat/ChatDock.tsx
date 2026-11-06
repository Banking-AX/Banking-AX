import { useEffect, useRef } from 'react'
import { useChatDock } from './chatDockContext'
import './chat-dock.css'

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
 * Right-hand AI dock of a customer page (docs/design-ref/Main.dc.html, AssistDock.dc.html). It floats over the page
 * (the column itself has no width), starts below the header and sticks to the top while scrolling.
 * Open: an empty slot the chat card is portaled into. Closed: only the round button with the unread badge.
 */
export function ChatDockColumn() {
  const { started, collapsed, unseen, expand, setSlot, focusRequest, clearFocusRequest } = useChatDock()
  const roundButtonRef = useRef<HTMLButtonElement>(null)

  // After [AI 도우미 닫기], focus the round button that reopens the dock.
  useEffect(() => {
    if (!collapsed || focusRequest !== 'expandButton') return
    roundButtonRef.current?.focus()
    clearFocusRequest()
  }, [collapsed, focusRequest, clearFocusRequest])

  if (!started) return null

  if (collapsed) {
    return <button ref={roundButtonRef} type="button" aria-label={unseen > 0 ? `AI 도우미 열기, 새 안내 ${unseen}건` : 'AI 도우미 열기'} onClick={expand} className="chat-dock-round">
      <span aria-hidden="true" className="chat-dock-round__icon">{DOCK_ROBOT}</span>
      {unseen > 0 && <span aria-hidden="true" className="chat-dock-badge chat-dock-badge--round">{unseen}</span>}
    </button>
  }

  return <aside aria-label="AI 금융 도우미" className="chat-dock-col">
    <div className="chat-dock-inner">
      <div ref={setSlot} className="chat-dock-slot"></div>
    </div>
  </aside>
}
