import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { isStaffPath } from '../../auth/roleHome'
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

const DOCK_GAP = 16

/** Top edge of the open dock: 16px below the site header, or 16px from the viewport top once the header scrolls away. */
function useDockTop(active: boolean): number {
  const [top, setTop] = useState(DOCK_GAP)
  useLayoutEffect(() => {
    if (!active) return
    const update = () => {
      const header = document.querySelector('.site-header')
      const bottom = header ? header.getBoundingClientRect().bottom : 0
      setTop(Math.max(0, bottom) + DOCK_GAP)
    }
    update()
    window.addEventListener('scroll', update, { passive: true })
    window.addEventListener('resize', update)
    return () => { window.removeEventListener('scroll', update); window.removeEventListener('resize', update) }
  }, [active])
  return top
}

/**
 * The AI dock for every customer screen (docs/design-ref/Main.dc.html, DockPanel.dc.html), mounted once in App.
 * Shown only to a signed-in customer and never on /login or staff routes. It floats over the page on the right;
 * closed (including before the first open) it is only the round button with the unread badge. /chat opens it.
 */
export function ChatDockHost() {
  const { status, user } = useAuth()
  const { pathname } = useLocation()
  const { started, collapsed, unseen, start, expand, setSlot, focusRequest, clearFocusRequest } = useChatDock()
  const roundButtonRef = useRef<HTMLButtonElement>(null)
  const hidden = !(status === 'authenticated' && user?.role === 'CUSTOMER') || pathname === '/login' || isStaffPath(pathname)
  const open = started && !collapsed
  const top = useDockTop(!hidden && open)

  // The chat route starts with the dock open (its first open is a new chat draft, as from the main CTA).
  const onChatRoute = /^\/chat(\/|$)/.test(pathname)
  useEffect(() => {
    if (!hidden && onChatRoute && !open) start()
    // Only on entering the chat route; closing the dock there afterwards is respected.
  }, [hidden, onChatRoute])

  // After [AI 도우미 닫기], focus the round button that reopens the dock.
  useEffect(() => {
    if (open || focusRequest !== 'expandButton') return
    roundButtonRef.current?.focus()
    clearFocusRequest()
  }, [open, focusRequest, clearFocusRequest])

  if (hidden) return null

  if (!open) {
    return <button ref={roundButtonRef} type="button" aria-label={unseen > 0 ? `AI 도우미 열기, 새 안내 ${unseen}건` : 'AI 도우미 열기'} onClick={started ? expand : start} className="chat-dock-round">
      <span aria-hidden="true" className="chat-dock-round__icon">{DOCK_ROBOT}</span>
      {unseen > 0 && <span aria-hidden="true" className="chat-dock-badge chat-dock-badge--round">{unseen}</span>}
    </button>
  }

  return <aside aria-label="AI 금융 도우미" className="chat-dock-col" style={{ '--chat-dock-top': `${top}px` } as CSSProperties}>
    <div className="chat-dock-inner">
      <div ref={setSlot} className="chat-dock-slot"></div>
    </div>
  </aside>
}
