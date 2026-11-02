// 미리보기용 Mock 샘플이며 실제 응답이 아니다. Rooms copied from docs/design-ref/ChatExpanded.dc.html.
import type { ChatRoomListItem } from './ChatRoomList'

/** Reference time for the design (2026.10.21 11:10 KST). Every sample time is derived from it. */
export const CHAT_ROOMS_NOW = new Date('2026-10-21T11:10:00+09:00')

const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR
const RETENTION = 7 * DAY
const at = (offsetMs: number) => new Date(CHAT_ROOMS_NOW.getTime() + offsetMs).toISOString()

const room1Activity = -(1 * HOUR + 50 * MINUTE) // 10.21 09:20
const room2Activity = -(19 * HOUR + 30 * MINUTE) // 10.20 15:40
const room3Activity = -(4 * DAY + 21 * HOUR + 10 * MINUTE) // 10.16 14:00
const room4Activity = -(9 * DAY + 1 * HOUR + 10 * MINUTE) // 10.12 10:00

export const CHAT_ROOMS_SAMPLE: ChatRoomListItem[] = [
  { id: 'room-1', title: '청약통장 해지 문의', createdAt: at(room1Activity - 5 * MINUTE), lastActivityAt: at(room1Activity), badge: { label: '보류 1 · 대기 1', tone: 'gray' }, deleteAt: at(room1Activity + RETENTION) },
  { id: 'room-2', title: '거래내역·충당순서·채무조정 상담', createdAt: at(room2Activity - 40 * MINUTE), lastActivityAt: at(room2Activity), badge: { label: '업무 3건 처리', tone: 'green' }, deleteAt: at(room2Activity + RETENTION) },
  { id: 'room-3', title: '최근 거래내역 조회', createdAt: at(room3Activity), lastActivityAt: at(room3Activity), deleteAt: at(2 * DAY) },
  // No title: checks the default "M월 D일 대화" label.
  { id: 'room-4', createdAt: at(room4Activity), lastActivityAt: at(room4Activity) },
]
