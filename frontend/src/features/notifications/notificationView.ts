import type { NotificationItem } from '../../api/notificationApi'

export type NotificationTone = 'success' | 'info' | 'question' | 'visit' | 'warning'

/** Icon/tone per spec 11-3 type code (ui-reference 57/58). Unknown codes fall back to the neutral info style. */
export function toneOf(type: string): { tone: NotificationTone; icon: string } {
  if (type === 'CASE_APPROVED' || type === 'B01_RESULT' || type === 'B02_RESULT') return { tone: 'success', icon: '✓' }
  if (type === 'CASE_REJECTED_REQUIREMENT' || type === 'CASE_REJECTED_DOCUMENT' || type === 'VISIT_MANUAL_ASSIGNMENT'
    || type === 'VISIT_CANCELED' || type === 'VISIT_EXPIRED') return { tone: 'warning', icon: '!' }
  if (type === 'CASE_CLARIFICATION_REQUEST') return { tone: 'question', icon: '?' }
  if (type === 'VISIT_CONFIRMED') return { tone: 'visit', icon: '▣' }
  return { tone: 'info', icon: 'i' }
}

const TASK_LABELS: Record<string, string> = {
  REPAYMENT_ORDER_CHANGE: 'B-01 충당순서 변경',
  HOUSING_SAVINGS_CLOSE: 'B-02 청약통장 해지',
  DEBT_ADJUSTMENT: 'C-01 채무조정 접수',
  INHERITANCE_DEPOSIT: 'C-02 상속예금 사전접수',
}

export function taskLabel(taskCode: string | null): string | null {
  return taskCode ? TASK_LABELS[taskCode] ?? null : null
}

const two = (value: number) => String(value).padStart(2, '0')

/** "10.21 11:05" in the viewer's local time, matching the mockup. */
export function formatReceivedAt(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return `${two(date.getMonth() + 1)}.${two(date.getDate())} ${two(date.getHours())}:${two(date.getMinutes())}`
}

/** Shows "이메일" only when the server recorded a sent email; no other status is presented as delivered. */
export function channelLabel(emailStatus: string): string {
  return emailStatus === 'SENT' ? '웹 알림 · 이메일' : '웹 알림'
}

export function badgeText(unread: number): string | null {
  if (unread <= 0) return null
  return unread > 99 ? '99+' : String(unread)
}

/** Related screen (NOTI-06/19). Only C-02 Cases have a customer detail page today; other items stay on this page. */
export function relatedPath(item: Pick<NotificationItem, 'taskCode' | 'caseId'>): string | null {
  if (item.taskCode === 'INHERITANCE_DEPOSIT' && item.caseId !== null) return `/tasks/c02/cases/${item.caseId}`
  return null
}
