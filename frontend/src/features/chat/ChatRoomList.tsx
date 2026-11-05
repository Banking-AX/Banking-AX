import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/** Times are ISO strings. title, badge and deleteAt are shown only when provided. */
export type ChatRoomListItem = {
  id: string
  lastActivityAt: string
  createdAt?: string
  title?: string
  badge?: { label: string; tone: 'gray' | 'green' }
  deleteAt?: string
}

type Props = {
  rooms: ChatRoomListItem[]
  onSelectRoom: (id: string) => void
  activeRoomId?: string
  now?: Date
  retentionDays?: number
  draftResetDays?: number
  onNewChat?: () => void
  /** Locks the new-chat button and the room buttons (e.g. while the chat waits for an answer). */
  disabled?: boolean
  /** Shown instead of the room groups, e.g. a loading or error notice. */
  notice?: ReactNode
  workRecordsHref?: string
}

type GroupKey = 'today' | 'yesterday' | 'week' | 'older'

const DAY_MS = 24 * 60 * 60 * 1000
const DELETE_WARNING_DAYS = 3

const pad2 = (value: number) => String(value).padStart(2, '0')
const startOfDay = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate())
/** Calendar days from a to b (positive when b is later). */
const dayDiff = (a: Date, b: Date) => Math.round((startOfDay(b).getTime() - startOfDay(a).getTime()) / DAY_MS)
const monthDay = (date: Date) => `${pad2(date.getMonth() + 1)}.${pad2(date.getDate())}`
const koreanDate = (date: Date) => `${date.getMonth() + 1}월 ${date.getDate()}일`
const clockTime = (date: Date) => date.toLocaleTimeString('ko-KR', { hour: 'numeric', minute: '2-digit' })

/** Rooms have no server title yet: "M월 D일 오후 3:12 대화" keeps rooms of the same day apart. */
export function defaultRoomTitle(createdAt: string): string {
  const date = new Date(createdAt)
  return `${koreanDate(date)} ${clockTime(date)} 대화`
}

/** "M월 D일 시작" from the room's creation time. */
export function roomStartedLabel(createdAt: string): string {
  return `${koreanDate(new Date(createdAt))} 시작`
}

function groupOf(activity: Date, now: Date): GroupKey {
  const days = dayDiff(activity, now)
  if (days <= 0) return 'today'
  if (days === 1) return 'yesterday'
  return days <= 7 ? 'week' : 'older'
}

function groupTitle(key: GroupKey, now: Date): string {
  if (key === 'today') return `오늘 · ${monthDay(now)}`
  if (key === 'yesterday') return `어제 · ${monthDay(new Date(now.getTime() - DAY_MS))}`
  return key === 'week' ? '이번 주' : '이전'
}

function roomTime(activity: Date, group: GroupKey): string {
  if (group === 'today' || group === 'yesterday') return clockTime(activity)
  return koreanDate(activity)
}

function deleteNotice(deleteAt: string | undefined, now: Date): { text: string; warn: boolean } | null {
  if (!deleteAt) return null
  const at = new Date(deleteAt)
  if (Number.isNaN(at.getTime())) return null
  const left = dayDiff(now, at)
  if (left < 0) return null
  return left <= DELETE_WARNING_DAYS
    ? { text: `${monthDay(at)} 삭제 예정 · ${left}일 남음`, warn: true }
    : { text: `${monthDay(at)} 삭제 예정`, warn: false }
}

const GROUP_ORDER: GroupKey[] = ['today', 'yesterday', 'week', 'older']

/** Room list body of the dock's "대화 기록" view (docs/design-ref/ChatExpanded.dc.html). */
export function ChatRoomList({ rooms, onSelectRoom, activeRoomId, now = new Date(), retentionDays, draftResetDays, onNewChat, disabled = false, notice, workRecordsHref = '/work' }: Props) {
  const sorted = rooms.slice().sort((a, b) => Date.parse(b.lastActivityAt) - Date.parse(a.lastActivityAt))
  const groups = GROUP_ORDER.map(key => ({ key, rooms: sorted.filter(room => groupOf(new Date(room.lastActivityAt), now) === key) }))
    .filter(group => group.rooms.length > 0)
  const hasPolicy = retentionDays !== undefined || draftResetDays !== undefined

  return <div className="chat-rooms">
    <div className="chat-rooms__body">
      {onNewChat && <button type="button" onClick={onNewChat} disabled={disabled} className="chat-rooms__new">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"></path></svg>새 대화 시작
      </button>}
      {hasPolicy && <div className="chat-rooms__policy">
        {retentionDays !== undefined && <span className="chat-rooms__policy-line"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#6B7280" strokeWidth="2" strokeLinecap="round" aria-hidden="true" className="chat-rooms__policy-icon"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v5l3 2"></path></svg><span>대화 내용은 마지막 대화 후 <strong>{retentionDays}일간</strong> 보관돼요.</span></span>}
        {draftResetDays !== undefined && <span className="chat-rooms__policy-line"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9A4A00" strokeWidth="2" strokeLinecap="round" aria-hidden="true" className="chat-rooms__policy-icon"><path d="M12 3l10 18H2z"></path><path d="M12 10v4M12 17h.01"></path></svg><span>제출하지 않은 업무는 마지막 활동 후 <strong>{draftResetDays}일</strong>이 지나면 입력 내용이 초기화돼요.</span></span>}
      </div>}

      <nav aria-label="최근 대화" className="chat-rooms__nav">
        {notice}
        {!notice && groups.length === 0 && <p className="chat-rooms__empty">아직 대화가 없어요.</p>}
        {!notice && groups.map(group => <div key={group.key} className="chat-rooms__group">
          <span id={`chat-rooms-group-${group.key}`} className="chat-rooms__group-title">{groupTitle(group.key, now)}</span>
          <ul aria-labelledby={`chat-rooms-group-${group.key}`} className="chat-rooms__list">
            {group.rooms.map(room => {
              const activity = new Date(room.lastActivityAt)
              const active = room.id === activeRoomId
              const deletion = deleteNotice(room.deleteAt, now)
              const title = room.title ?? defaultRoomTitle(room.createdAt ?? room.lastActivityAt)
              return <li key={room.id} className="chat-rooms__item">
                <button type="button" onClick={() => onSelectRoom(room.id)} disabled={disabled} aria-current={active ? 'true' : undefined} className={`chat-rooms__link${active ? ' chat-rooms__link--active' : ''}`}>
                  <span className="chat-rooms__row"><strong className="chat-rooms__title">{title}</strong><span className="chat-rooms__time">{roomTime(activity, group.key)}</span></span>
                  {(room.badge || deletion) && <span className="chat-rooms__meta">
                    {room.badge && <span className={`chat-rooms__badge chat-rooms__badge--${room.badge.tone}`}>{room.badge.label}</span>}
                    {deletion && <span className={deletion.warn ? 'chat-rooms__delete--warn' : undefined}>{deletion.text}</span>}
                  </span>}
                </button>
              </li>
            })}
          </ul>
        </div>)}
      </nav>
    </div>

    <div className="chat-rooms__bottom">
      <Link to={workRecordsHref} className="chat-rooms__records">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="5" y="4" width="14" height="17" rx="2"></rect><path d="M9 4h6v3H9zM9 12h6M9 16h4"></path></svg>업무 요청 기록
      </Link>
      {retentionDays !== undefined && <span className="chat-rooms__bottom-note">대화가 삭제돼도 업무 기록은 계속 남아요</span>}
    </div>
  </div>
}
