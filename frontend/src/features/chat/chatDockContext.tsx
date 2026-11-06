import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { screenForPath } from './chatGuideCopy'

/** What the current page tells the dock: the "현재 화면" line and the "이 화면 안내" card. */
export type DockScreen = { name: string; guide?: ReactNode }

/** A focus move requested by a user action; consumers clear it once handled. */
export type DockFocusRequest = 'input' | 'expandButton' | null

type ChatDockValue = {
  started: boolean
  collapsed: boolean
  /** AI answers that arrived while the dock was collapsed. */
  unseen: number
  start: () => void
  collapse: () => void
  expand: () => void
  noteAgentReply: () => void
  /** The dock's slot element; the chat card is portaled into it. */
  slot: HTMLElement | null
  setSlot: (element: HTMLElement | null) => void
  /** The page's own screen (setScreen) or, when it sets none, the route table in chatGuideCopy.ts. */
  screen: DockScreen | null
  setScreen: (screen: DockScreen | null) => void
  focusRequest: DockFocusRequest
  clearFocusRequest: () => void
}

const ChatDockContext = createContext<ChatDockValue | null>(null)

export function ChatDockProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const [started, setStarted] = useState(false)
  const [collapsed, setCollapsed] = useState(false)
  const [unseen, setUnseen] = useState(0)
  const [slot, setSlot] = useState<HTMLElement | null>(null)
  const [pageScreen, setScreen] = useState<DockScreen | null>(null)
  const { pathname } = useLocation()
  const screen = pageScreen ?? screenForPath(pathname)
  const [focusRequest, setFocusRequest] = useState<DockFocusRequest>(null)
  const collapsedRef = useRef(collapsed)
  collapsedRef.current = collapsed

  // A different user (or logout) starts from a closed dock.
  const userId = user?.userId ?? null
  useEffect(() => {
    setStarted(false)
    setCollapsed(false)
    setUnseen(0)
    setFocusRequest(null)
  }, [userId])

  const start = useCallback(() => { setStarted(true); setCollapsed(false); setUnseen(0); setFocusRequest('input') }, [])
  const collapse = useCallback(() => { setCollapsed(true); setFocusRequest('expandButton') }, [])
  const expand = useCallback(() => { setCollapsed(false); setUnseen(0); setFocusRequest('input') }, [])
  const noteAgentReply = useCallback(() => { if (collapsedRef.current) setUnseen(count => count + 1) }, [])
  const clearFocusRequest = useCallback(() => setFocusRequest(null), [])

  const value = useMemo<ChatDockValue>(() => ({
    started, collapsed, unseen, start, collapse, expand, noteAgentReply, slot, setSlot, screen, setScreen, focusRequest, clearFocusRequest,
  }), [started, collapsed, unseen, start, collapse, expand, noteAgentReply, slot, screen, focusRequest, clearFocusRequest])

  return <ChatDockContext.Provider value={value}>{children}</ChatDockContext.Provider>
}

export function useChatDock(): ChatDockValue {
  const value = useContext(ChatDockContext)
  if (!value) throw new Error('useChatDock must be used inside ChatDockProvider.')
  return value
}
