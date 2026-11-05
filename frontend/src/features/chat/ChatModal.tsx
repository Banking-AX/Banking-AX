import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import { ApiError } from '../../api/client'
import { toUserMessage } from '../../api/errorMessages'
import { chatApi, replyFromAgentRun, replyFromChatMessage, type AgentRunRequest, type ChatMessage, type ChatReply, type ChatRoom, type ChatTaskAction } from '../../api/chat'
import { BotAvatar, BotMessage, ChatReplyView, replyLabel, replyText } from './ChatReplyView'
import { useNavigate } from 'react-router-dom'
import { DOCK_STAGES, DockStageChip, DOCK_ROBOT, type DockStage } from './ChatDock'
import { useChatDock } from './chatDockContext'
import { ChatHistoryView } from './ChatHistoryView'
import { defaultRoomTitle, roomStartedLabel } from './ChatRoomList'

type Entry =
  | { key: string; kind: 'message'; message: ChatMessage; reply?: ChatReply }
  | { key: string; kind: 'pendingUser'; request: AgentRunRequest; sentAt: string }
  | { key: string; kind: 'error'; request: AgentRunRequest; text: string; retryable: boolean }

/** chat: default dock chat. list: [대화 기록] room list. room: a room picked from the list. */
type DockView = 'chat' | 'list' | 'room'

// Main board list (docs/design-ref/Main.dc.html): tag + title; the title is sent as the message.
const QUICK_TASKS = [
  { badge: 'A · 조회', tone: 'a', title: '최근 거래내역 조회하기' },
  { badge: 'A · 조회', tone: 'a', title: '대출계좌 정보 조회하기' },
  { badge: 'B · 자동처리', tone: 'b', title: '채무변제 충당순서 변경하기' },
  { badge: 'B · 자동처리', tone: 'b', title: '청약통장 해지하기' },
  { badge: 'C · 직원 검토', tone: 'c', title: '채무조정 접수하기' },
  { badge: 'C · 직원 검토', tone: 'c', title: '상속예금 사전접수' },
] as const

const MIN_LENGTH = 2
const MAX_LENGTH = 2000

const formatTime = (date: Date) => date.toLocaleTimeString('ko-KR', { hour: 'numeric', minute: '2-digit' })
const formatServerTime = (value: string) => new Date(value).toLocaleTimeString('ko-KR', {
  hour: 'numeric', minute: '2-digit', timeZone: 'Asia/Seoul',
})
const formatDate = (date: Date) => date.toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })

/**
 * Customer chat, mounted once in App so the conversation survives page changes. The card is portaled
 * into the current page's dock slot (ChatDockColumn); without a slot it keeps its state but renders nothing.
 */
export function ChatModal() {
  const navigate = useNavigate()
  const { started: open, collapsed, slot, screen, collapse, noteAgentReply, focusRequest, clearFocusRequest } = useChatDock()
  const visible = open && !collapsed && slot !== null
  const [openedAt, setOpenedAt] = useState<Date | null>(null)
  const [entries, setEntries] = useState<Entry[]>([])
  const [draft, setDraft] = useState('')
  const [hint, setHint] = useState('')
  const [pending, setPending] = useState(false)
  const [roomId, setRoomId] = useState<number | null>(null)
  const [roomLoading, setRoomLoading] = useState(false)
  const [roomError, setRoomError] = useState('')
  const [announcement, setAnnouncement] = useState<{ id: number; text: string } | null>(null)
  const [actionPending, setActionPending] = useState<string | null>(null)
  const [actionError, setActionError] = useState('')
  const [view, setView] = useState<DockView>('chat')
  // The room on screen. null means a new-chat draft: no server room exists until the first message is sent.
  // While a picked room loads, currentRoom is set but roomId stays null so sending stays locked.
  const [currentRoom, setCurrentRoom] = useState<ChatRoom | null>(null)
  const [viewFocus, setViewFocus] = useState<'title' | 'historyButton' | null>(null)

  const dialogRef = useRef<HTMLElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const historyButtonRef = useRef<HTMLButtonElement>(null)
  const roomRequestRef = useRef<number | null>(null)
  const nextAnnouncementId = useRef(1)
  const controllerRef = useRef<AbortController | null>(null)
  const pendingRef = useRef(false)
  const roomCreationRef = useRef<Promise<ChatRoom> | null>(null)
  const mountedRef = useRef(true)

  useEffect(() => { if (open && !openedAt) setOpenedAt(new Date()) }, [open, openedAt])

  // Closing keeps the in-flight request; only unmount (e.g. logout) aborts it.
  useEffect(() => {
    mountedRef.current = true
    return () => { mountedRef.current = false; controllerRef.current?.abort() }
  }, [])

  const stage: DockStage = pending ? 'working' : 'idle'
  const isDraft = currentRoom === null
  const inputLocked = DOCK_STAGES[stage].locksInput || roomLoading || (roomId === null && !isDraft)

  // After an answer, give the input back only if the customer was using the card (never steal page focus).
  useEffect(() => {
    if (visible && openedAt && !pending && dialogRef.current?.contains(document.activeElement)) inputRef.current?.focus()
  }, [visible, openedAt, pending])

  // [AI 금융업무 시작하기] or [도크 펼치기]: focus the input, or the card until the room is ready.
  useEffect(() => {
    if (!visible || focusRequest !== 'input') return
    if (view === 'list') { dialogRef.current?.focus(); clearFocusRequest(); return }
    if (!inputLocked && inputRef.current) { inputRef.current.focus(); clearFocusRequest() }
    else dialogRef.current?.focus()
  }, [visible, focusRequest, inputLocked, clearFocusRequest, view])

  // View changes made by the customer move focus to the new view's title (or back to [대화 기록]).
  useEffect(() => {
    if (!visible || !viewFocus) return
    if (viewFocus === 'historyButton') historyButtonRef.current?.focus()
    else document.getElementById(view === 'list' ? 'chat-history-title' : 'chat-title')?.focus()
    setViewFocus(null)
  }, [visible, viewFocus, view])

  // A new slot (page change) or expanding re-creates the card DOM: keep the latest message in view.
  useEffect(() => {
    const body = bodyRef.current
    if (body) body.scrollTop = body.scrollHeight
  }, [entries, pending, visible, slot, openedAt, view])

  const mergeMessages = (messages: ChatMessage[], replies = new Map<number, ChatReply>()) => {
    setEntries(current => {
      const transient = current.filter(entry => entry.kind !== 'message')
      const existing = new Map(current.flatMap(entry => entry.kind === 'message' ? [[entry.message.id, entry] as const] : []))
      for (const message of messages) {
        const previous = existing.get(message.id)
        existing.set(message.id, {
          key: `message-${message.id}`, kind: 'message', message, reply: replies.get(message.id) ?? previous?.reply,
        })
      }
      return [
        ...[...existing.values()].sort((left, right) => left.message.seq - right.message.seq),
        ...transient,
      ] as Entry[]
    })
  }

  const refreshMessages = async (currentRoomId: number) => {
    const messages = await chatApi.listMessages(currentRoomId)
    mergeMessages(messages)
  }

  // The first message of a draft creates the room right before it is sent. Concurrent callers share one creation.
  const ensureRoom = async (): Promise<number> => {
    if (roomId !== null) return roomId
    if (!roomCreationRef.current) {
      roomCreationRef.current = chatApi.createRoom().finally(() => { roomCreationRef.current = null })
    }
    const room = await roomCreationRef.current
    if (mountedRef.current) {
      roomRequestRef.current = room.id
      setCurrentRoom(room)
      setRoomId(room.id)
    }
    return room.id
  }

  const dispatch = (request: AgentRunRequest) => {
    if (pendingRef.current || (roomId === null && !isDraft)) return
    const controller = new AbortController()
    controllerRef.current = controller
    pendingRef.current = true
    setPending(true)
    // The input becomes disabled; keep focus inside the dialog meanwhile.
    dialogRef.current?.focus()
    let usedRoomId: number | null = roomId
    ;(async () => {
      const targetRoomId = await ensureRoom()
      usedRoomId = targetRoomId
      return chatApi.runAgent(targetRoomId, request, { signal: controller.signal })
    })()
      .then(run => {
        if (!run.agentMessage || !run.agentMessageSaved) {
          mergeMessages([run.customerMessage])
          throw new ApiError('AGENT_MESSAGE_NOT_SAVED', 502, 'Agent answer was not saved.')
        }
        if (run.agentResult.outcome === 'STARTED' && run.agentResult.executionStatus && !run.financialOperationCompleted) {
          mergeMessages([run.customerMessage, run.agentMessage])
          throw new ApiError('FINANCIAL_OPERATION_NOT_COMPLETED', 502, 'Financial operation completion was not confirmed.')
        }
        const reply = replyFromAgentRun(run)
        mergeMessages([run.customerMessage, run.agentMessage], new Map([[run.agentMessage.id, reply]]))
        // Errors are transient UI state, not persisted chat messages. Keeping a
        // previous failure after a later successful reply makes it render below
        // the new task-action button and falsely imply that reply failed.
        setEntries(list => list.filter(entry => entry.kind === 'message'))
        noteAgentReply()
        setAnnouncement({ id: nextAnnouncementId.current++, text: `${replyLabel(reply)}: ${replyText(reply)}` })
      })
      .catch(async (error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (usedRoomId !== null) await refreshMessages(usedRoomId).catch(() => undefined)
        const status = error instanceof ApiError ? error.status : 0
        // A failed room creation sent nothing, so [다시 시도] is always safe; the retry reuses the same request ids.
        const roomCreationFailed = usedRoomId === null
        setEntries(list => [
          ...list.filter(entry => entry.kind === 'message' || entry.request.operationRequestId !== request.operationRequestId),
          { key: `error-${request.operationRequestId}`, kind: 'error', request,
            text: toUserMessage(error), retryable: roomCreationFailed || status === 0 || status === 502 || status === 503 },
        ])
      })
      .finally(() => {
        if (controllerRef.current !== controller) return
        controllerRef.current = null
        pendingRef.current = false
        setPending(false)
      })
  }

  const send = (raw: string) => {
    if (pendingRef.current) return
    const message = raw.trim()
    if (message.length < MIN_LENGTH) { setHint('두 글자 이상 입력해 주세요.'); return }
    if (roomId === null && !isDraft) { setHint('채팅방을 준비하는 중이에요. 잠시 후 다시 시도해 주세요.'); return }
    setHint('')
    setDraft('')
    const request = { messageRequestId: crypto.randomUUID(), operationRequestId: crypto.randomUUID(), content: message }
    setEntries(list => [...list, { key: `pending-${request.messageRequestId}`, kind: 'pendingUser', request, sentAt: new Date().toISOString() }])
    dispatch(request)
  }

  const retry = (entry: Extract<Entry, { kind: 'error' }>) => {
    if (pendingRef.current) return
    setEntries(list => list.filter(item => item.key !== entry.key))
    dispatch(entry.request)
  }

  const startTaskAction = async (action: ChatTaskAction) => {
    if (actionPending || (roomId === null && !isDraft)) return
    setActionPending(action.actionRequestId)
    setActionError('')
    try {
      const targetRoomId = await ensureRoom()
      const result = await chatApi.startTaskAction(targetRoomId, action)
      if (result.taskCode !== 'REPAYMENT_ORDER_CHANGE') throw new ApiError('INVALID_RESPONSE', 502)
      navigate(`/tasks/b01/${result.workItemId}`, { state: { chatRoomId: targetRoomId } })
    } catch (error) {
      setActionError(toUserMessage(error))
    } finally {
      setActionPending(null)
    }
  }

  const loadRoom = async (room: ChatRoom) => {
    roomRequestRef.current = room.id
    setCurrentRoom(room)
    setRoomId(null)
    setEntries([])
    setRoomError('')
    setActionError('')
    setHint('')
    setRoomLoading(true)
    try {
      const messages = await chatApi.listMessages(room.id)
      if (!mountedRef.current || roomRequestRef.current !== room.id) return
      setRoomId(room.id)
      setEntries(messages.map(message => ({ key: `message-${message.id}`, kind: 'message', message })))
    } catch (error) {
      if (mountedRef.current && roomRequestRef.current === room.id) setRoomError(toUserMessage(error))
    } finally {
      if (mountedRef.current && roomRequestRef.current === room.id) setRoomLoading(false)
    }
  }

  const retryRoom = () => { if (currentRoom && roomId === null) void loadRoom(currentRoom) }

  const historyLocked = DOCK_STAGES[stage].locksInput

  const openHistory = () => {
    if (DOCK_STAGES[stage].locksInput) return
    setView('list')
    setViewFocus('title')
  }

  const backToChat = () => { setView('chat'); setViewFocus('historyButton') }

  const selectRoom = (room: ChatRoom) => {
    if (historyLocked) return
    setView('room')
    setViewFocus('title')
    if (room.id !== currentRoom?.id || roomId === null) void loadRoom(room)
  }

  // [새 대화 시작] only opens a draft; the server room is created when the first message is sent.
  const startNewChat = () => {
    if (historyLocked) return
    roomRequestRef.current = null
    setCurrentRoom(null)
    setRoomId(null)
    setEntries([])
    setRoomError('')
    setRoomLoading(false)
    setActionError('')
    setHint('')
    setView('chat')
    setViewFocus('title')
  }

  const onInputKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    // keyCode 229: IME composition in browsers that do not set isComposing on keydown.
    if (event.key !== 'Enter' || event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return
    event.preventDefault()
    send(draft)
  }

  if (!visible || !openedAt || !slot) return null

  if (view === 'list') {
    return createPortal(<section ref={dialogRef} aria-labelledby="chat-history-title" tabIndex={-1} className="chat-dock-card">
      <ChatHistoryView activeRoomId={currentRoom?.id ?? null} locked={historyLocked}
        onClose={backToChat} onSelectRoom={selectRoom} onNewChat={startNewChat} />
    </section>, slot)
  }

  const roomTitle = currentRoom ? defaultRoomTitle(currentRoom.createdAt) : ''

  return createPortal(<section ref={dialogRef} aria-labelledby="chat-title" tabIndex={-1} className={`chat-dock-card${view === 'room' ? ' chat-dock-card--room' : ''}`}>
      <header className="chat-dock__head">
        {view === 'room' ? <div className="chat-dock__bar chat-dock__bar--nav">
          <button type="button" aria-label="대화 목록으로" onClick={() => { setView('list'); setViewFocus('title') }} className="chat-dock__back">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M15 6l-6 6 6 6"></path></svg>
          </button>
          <div className="chat-dock__titles chat-dock__titles--stack">
            <h2 id="chat-title" tabIndex={-1} className="chat-dock__title">{roomTitle}</h2>
            {currentRoom && <span className="chat-dock__sub">{roomStartedLabel(currentRoom.createdAt)}</span>}
            <DockStageChip stage={stage} />
          </div>
          <button type="button" aria-label="도크 접기" onClick={collapse} className="chat-dock__collapse">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6"></path></svg>
          </button>
        </div> : <div className="chat-dock__bar">
          <span className="chat-dock__logo">{DOCK_ROBOT}</span>
          <div className="chat-dock__titles">
            <h2 id="chat-title" tabIndex={-1} className="chat-dock__title">AI 금융 도우미</h2>
            <DockStageChip stage={stage} />
          </div>
          <button ref={historyButtonRef} type="button" onClick={openHistory} disabled={DOCK_STAGES[stage].locksInput} className="chat-dock__history-button">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 12a9 9 0 1 0 3-6.7L3 8"></path><path d="M3 3v5h5M12 7v5l3 2"></path></svg>
            대화 기록</button>
          <button type="button" aria-label="도크 접기" onClick={collapse} className="chat-dock__collapse">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 6l6 6-6 6"></path></svg>
          </button>
        </div>}
        {view === 'room'
          ? <div className="chat-dock__screen">현재 화면: <strong>대화방 · {roomTitle}</strong></div>
          : screen && <div className="chat-dock__screen">현재 화면: <strong>{screen.name}</strong></div>}
      </header>

      <div ref={bodyRef} className="chat-body">
        {view === 'chat' && <>
        <div className="chat-date"><span className="chat-date__line"></span>{formatDate(openedAt)}<span className="chat-date__line"></span></div>

        <BotMessage label="AI 금융 도우미" time={formatTime(openedAt)}>안녕하세요! AX AI 금융 도우미입니다.<br />어떤 금융업무를 도와드릴까요?</BotMessage>

        {screen?.guide && <div className="chat-dock__guide">
          <span className="chat-dock__guide-tag"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M12 11v5M12 7.5h.01"></path></svg>이 화면 안내</span>
          <span className="chat-dock__guide-text">{screen.guide}</span>
        </div>}

        <div className="chat-dock__quick">
          <span className="chat-quick__title">자주 찾는 업무</span>
          {QUICK_TASKS.map(task => <button key={task.title} type="button" disabled={DOCK_STAGES[stage].locksInput} onClick={() => send(task.title)} className="chat-dock__task">
            <span className={`chat-card__badge chat-card__badge--${task.tone}`}>{task.badge}</span>
            <span className="chat-dock__task-title">{task.title}</span>
          </button>)}
          <p className="chat-dock__tip">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true" className="chat-dock__tip-icon"><circle cx="12" cy="12" r="9"></circle><path d="M12 11v5M12 7.5h.01"></path></svg>
            여러 업무를 한 번에 말씀하셔도 돼요. 하나씩 나눠 진행하고, 끝나면 다음 업무를 진행할지 여쭤봐요.</p>
        </div>
        </>}

        {roomError && <div role="alert" className="chat-error__box">
          <span className="chat-error__text">{roomError}</span>
          <button type="button" onClick={retryRoom} className="chat-error__retry">다시 시도</button>
        </div>}

        {entries.map(entry => {
          if (entry.kind === 'pendingUser') return <div key={entry.key} className="chat-user">
            <div className="chat-bubble chat-bubble--user">{entry.request.content}</div>
            <span className="chat-time">{formatServerTime(entry.sentAt)}</span>
          </div>
          if (entry.kind === 'message') {
            if (entry.message.senderType === 'CUSTOMER') return <div key={entry.key} className="chat-user">
              <div className="chat-bubble chat-bubble--user">{entry.message.content}</div>
              <span className="chat-time">{formatServerTime(entry.message.sentAt)}</span>
            </div>
            const reply = entry.reply ?? replyFromChatMessage(entry.message)
            return <ChatReplyView key={entry.key} reply={reply} time={formatServerTime(entry.message.sentAt)}
              actionPending={reply.kind === 'taskAction' && actionPending === reply.action.actionRequestId}
              onTaskAction={reply.kind === 'taskAction' ? () => startTaskAction(reply.action) : undefined} />
          }
          return <div key={entry.key} className="chat-error">
            <span className="chat-spacer" aria-hidden="true"></span>
            <div role="alert" className="chat-error__box">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#B42318" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v6M12 16.5h.01"></path></svg>
              <span className="chat-error__text">{entry.text}</span>
              {entry.retryable && <button type="button" disabled={DOCK_STAGES[stage].locksInput} onClick={() => retry(entry)} className="chat-error__retry">다시 시도</button>}
            </div>
          </div>
        })}

        {actionError && <div role="alert" className="chat-error__box"><span className="chat-error__text">{actionError}</span></div>}

        {(pending || roomLoading) && <div className="chat-typing">
          <BotAvatar />
          <div role="status" className="chat-typing__bubble">
            <span className="chat-typing__dot"></span>
            <span className="chat-typing__dot"></span>
            <span className="chat-typing__dot"></span>
            <span className="chat-typing__text">{roomLoading ? '이전 대화를 불러오고 있어요' : '요청 내용을 확인하고 있어요 · 답변 중에는 입력이 잠겨요'}</span>
          </div>
        </div>}

        {/* A new keyed node is announced even when the text repeats. */}
        <div aria-live="polite" className="chat-sr-only">{announcement && <span key={announcement.id}>{announcement.text}</span>}</div>
      </div>

      <footer className="chat-footer">
        <div className="chat-composer">
          <label htmlFor="chat-input" className="chat-sr-only">메시지 입력</label>
          <input id="chat-input" ref={inputRef} type="text" value={draft} maxLength={MAX_LENGTH} disabled={inputLocked}
            onChange={e => { setDraft(e.target.value); if (hint) setHint('') }} onKeyDown={onInputKeyDown}
            aria-describedby="chat-input-hint"
            placeholder={pending ? 'AI가 답변을 준비하는 동안에는 입력할 수 없어요' : roomLoading ? '이전 대화를 불러오고 있어요' : '하고 싶은 업무를 말해 보세요'}
            className="chat-input" />
          <button type="button" aria-label="메시지 보내기" onClick={() => send(draft)} disabled={inputLocked} className="chat-send">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M4 12l16-8-6 16-3-7z"></path><path d="M11 13l9-9"></path></svg>
          </button>
        </div>
        <p id="chat-input-hint" aria-live="polite" className="chat-hint">{hint}</p>
        <p className="chat-footnote">AI 안내는 참고용이에요. 서명·인증·동의는 직접 해 주세요.</p>
      </footer>
    </section>, slot)
}
