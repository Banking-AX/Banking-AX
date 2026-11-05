import { apiFetch } from './client'
import { taskBffEnabled } from './taskApi'

/** Mirrors NotificationController.Item. Times are UTC instants; emailStatus is stored as-is (no SMTP sender yet). */
export type NotificationItem = {
  id: number; caseId: number | null; workItemId: number | null; taskCode: string | null
  type: string; title: string; content: string; readAt: string | null; createdAt: string; emailStatus: string
}
export type NotificationSummary = { total: number; unread: number }
export type NotificationPage = { items: NotificationItem[]; hasMore: boolean; summary: NotificationSummary }

const BASE = '/api/v1/customer/notifications'
const rid = (action: string) => `notification-${action}-${crypto.randomUUID()}`

function requireBff() {
  if (!taskBffEnabled) throw new Error('알림은 로그인 BFF 연결 후 사용할 수 있어요.')
}

/** The customer is taken from the JWT on the server; no customer id is ever sent from the browser. */
export const notificationApi = {
  async list(options: { unreadOnly?: boolean; beforeId?: number; limit?: number } = {}) {
    requireBff()
    const query = new URLSearchParams({ requestId: rid('list'), unreadOnly: String(options.unreadOnly ?? false), limit: String(options.limit ?? 20) })
    if (options.beforeId !== undefined) query.set('beforeId', String(options.beforeId))
    return apiFetch<NotificationPage>(`${BASE}?${query}`)
  },
  async summary() {
    requireBff()
    return apiFetch<NotificationSummary>(`${BASE}/summary?requestId=${encodeURIComponent(rid('summary'))}`)
  },
  async read(id: number) {
    requireBff()
    return apiFetch<null>(`${BASE}/${id}/read?requestId=${encodeURIComponent(rid('read'))}`, { method: 'POST' })
  },
  async readAll() {
    requireBff()
    return apiFetch<{ updated: number; summary: NotificationSummary }>(`${BASE}/read-all?requestId=${encodeURIComponent(rid('read-all'))}`, { method: 'POST' })
  },
}
