import { useCallback, useEffect, useState } from 'react'
import { toUserMessage } from '../../api/errorMessages'
import { chatApi, type ChatRoom } from '../../api/chat'
import { ChatRoomList, type ChatRoomListItem } from './ChatRoomList'

type Props = {
  activeRoomId: number | null
  /** Room buttons and [새 대화 시작] are locked while an answer is pending. */
  locked: boolean
  /** [대화 기록 닫기]: back to the chat view. The list view has no collapse button. */
  onClose: () => void
  onSelectRoom: (room: ChatRoom) => void
  onNewChat: () => void
}

type ListState = { kind: 'loading' } | { kind: 'ready'; rooms: ChatRoom[] } | { kind: 'error'; message: string }

// 정책 표시, 보관·삭제 스케줄러는 서버 미구현. Policy copy from the spec (docs/design-ref/ChatExpanded.dc.html):
// chats are kept 7 days after the last activity and unsubmitted task input resets after 3 days.
const RETENTION_DAYS = 7
const DRAFT_RESET_DAYS = 3
const DAY_MS = 24 * 60 * 60 * 1000

// The server has no room title or task badge, so neither is mapped. The deletion date shown is the policy
// estimate (last activity + 7 days); nothing is deleted by the server yet.
const toListItem = (room: ChatRoom): ChatRoomListItem => {
  const lastActivityAt = room.lastActivityAt ?? room.createdAt
  return {
    id: String(room.id), createdAt: room.createdAt, lastActivityAt,
    deleteAt: new Date(Date.parse(lastActivityAt) + RETENTION_DAYS * DAY_MS).toISOString(),
  }
}

/**
 * The dock's "대화 기록" view (docs/design-ref/ChatExpanded.dc.html): replaces the chat inside the dock card.
 * Room titles, task badges and task progress stay hidden until the server provides them.
 */
export function ChatHistoryView({ activeRoomId, locked, onClose, onSelectRoom, onNewChat }: Props) {
  const [state, setState] = useState<ListState>({ kind: 'loading' })

  const load = useCallback(() => {
    let active = true
    setState({ kind: 'loading' })
    chatApi.listRooms()
      // Rooms without any saved message are left out: ChatService sets lastActivityAt only when a message is saved.
      .then(rooms => { if (active) setState({ kind: 'ready', rooms: rooms.filter(room => room.lastActivityAt !== null) }) })
      .catch(error => { if (active) setState({ kind: 'error', message: toUserMessage(error) }) })
    return () => { active = false }
  }, [])

  useEffect(() => load(), [load])

  const rooms = state.kind === 'ready' ? state.rooms : []
  const select = (id: string) => {
    const room = rooms.find(item => String(item.id) === id)
    if (room) onSelectRoom(room)
  }

  return <>
    <header className="chat-dock__head">
      <div className="chat-dock__bar chat-dock__bar--list">
        <div className="chat-dock__titles chat-dock__titles--stack">
          <h2 id="chat-history-title" tabIndex={-1} className="chat-dock__title">대화 기록</h2>
        </div>
        <button type="button" aria-label="대화 기록 닫기" onClick={onClose} className="chat-dock__close">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"></path></svg>
        </button>
      </div>
      <div className="chat-dock__screen">현재 화면: <strong>대화 기록 (목록)</strong></div>
    </header>

    <div className="chat-dock__history">
      <ChatRoomList rooms={rooms.map(toListItem)} activeRoomId={activeRoomId === null ? undefined : String(activeRoomId)}
        onSelectRoom={select} onNewChat={onNewChat} disabled={locked}
        retentionDays={RETENTION_DAYS} draftResetDays={DRAFT_RESET_DAYS}
        notice={state.kind === 'loading'
          ? <p role="status" className="chat-rooms__empty">대화 목록을 불러오고 있어요.</p>
          : state.kind === 'error'
            ? <div role="alert" className="chat-error__box">
              <span className="chat-error__text">{state.message}</span>
              <button type="button" onClick={() => load()} className="chat-error__retry">다시 시도</button>
            </div>
            : undefined} />
    </div>
  </>
}
