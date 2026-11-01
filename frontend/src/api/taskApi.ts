import { apiFetch, type ApiEnvelope } from './client'

/**
 * Browser-facing task contract.  Calls are deliberately same-origin: the
 * authenticated BFF derives the actor from the JWT and injects the internal
 * gateway credential server-side. Do not add that credential here.
 */
export const taskBffEnabled = import.meta.env.VITE_BFF_TASKS_ENABLED === 'true'

export type Transaction = { transactionId: number; occurredAt: string; direction: 'IN' | 'OUT'; amount: number; counterparty: string | null; memo: string | null }
export type TransactionSearchResult = { accountId: number; accountNoMasked: string; accountName: string; timeZone: string; transactions: Transaction[]; page: number; size: number; totalElements: number; totalPages: number }
export type A01Request = { requestId: string; workItemId: number; accountId: number; startDate: string; endDate: string; transactionType: 'IN' | 'OUT' | null; minimumAmount: string | null; maximumAmount: string | null; keyword: string | null; sortOrder: 'RECENT' | 'OLDEST'; page: number; size: number }

export type B02Prepare = { requestId: string; account: number; waive: boolean; taxed: boolean | null }
export type B02Otp = { requestId: string; version: number; consent: boolean; signed: boolean; phone: string | null; workItemId: number }
export type B02Execute = { requestId: string; version: number; waive: boolean; taxed: boolean | null; binding: string; workItemId: number }

export type C02Heir = { personRef: string; name: string; birthDate: string | null; relationship: string; applicant: boolean; higherOrderOrSubrogation: boolean; visitStatus: 'ATTENDING' | 'ABSENT' | 'UNCONFIRMED_ABSENT'; delegationMethod: 'NONE' | 'DOCUMENT' | 'RECEIPT'; delegationReceiptNo: string | null }
export type C02Prepare = { requestId: string; workItemId: number; registeredPhone: string | null; decedent: { name: string; birthDate: string | null; deathDate: string; depositTotal: number }; heirs: C02Heir[]; documentStatuses: Record<string, string> }
export type NotificationItem = { id: number; caseId: number | null; type: string; title: string; content: string; readAt: string | null; createdAt: string }
export type Reservation = { id: number; caseId: number; branchId: number; staffId: number; startsAt: string; status: string }
export type ReservationCreate = { requestId: string; caseId: number; branchId: number; staffId: number | null; startsAt: string }

function requireBff() {
  if (!taskBffEnabled) throw new Error('업무 API는 로그인 BFF 연결 후 사용할 수 있습니다.')
}

/** BFF must add customerId from session; the browser never sends actor headers. */
export async function searchTransactions(request: A01Request): Promise<ApiEnvelope<TransactionSearchResult>> {
  requireBff()
  return apiFetch<TransactionSearchResult>('/api/v1/customer/a01/transaction-search', { method: 'POST', body: JSON.stringify(request) })
}

export const b02Api = {
  async prepare(body: B02Prepare) { requireBff(); return apiFetch<unknown>('/api/v1/customer/b02/prepare', { method: 'POST', body: JSON.stringify(body) }) },
  async issueOtp(accountId: number, body: B02Otp) { requireBff(); return apiFetch<{ challengeId: string }>('/api/v1/customer/b02/' + accountId + '/otp', { method: 'POST', body: JSON.stringify(body) }) },
  async verifyOtp(challenge: string, otp: string, requestId: string) { requireBff(); return apiFetch<{ bindingId: string }>('/api/v1/customer/b02/otp/verify', { method: 'POST', body: JSON.stringify({ requestId, challenge, otp }) }) },
  async execute(accountId: number, body: B02Execute) { requireBff(); return apiFetch<unknown>('/api/v1/customer/b02/' + accountId + '/execute', { method: 'POST', body: JSON.stringify(body) }) },
}

export const c02Api = {
  async prepare(body: C02Prepare) { requireBff(); return apiFetch<unknown>('/api/v1/customer/c02/prepare', { method: 'POST', body: JSON.stringify(body) }) },
  async staffCase(caseId: number, requestId: string) { requireBff(); return apiFetch<unknown>('/api/v1/staff/c02/' + caseId + '/staff-case?requestId=' + encodeURIComponent(requestId)) },
  async staffDecision(caseId: number, body: { revision: number; requestId: string; outcome: 'PROCEED' | 'REQUIREMENT_FAILED' | 'DOCUMENT_INSUFFICIENT'; customerMessage: string }) { requireBff(); return apiFetch<unknown>('/api/v1/staff/c02/' + caseId + '/staff-decision', { method: 'POST', body: JSON.stringify(body) }) },
  async notifications(requestId: string) { requireBff(); return apiFetch<NotificationItem[]>('/api/v1/customer/notifications?requestId=' + encodeURIComponent(requestId)) },
  async readNotification(id: number, requestId: string) { requireBff(); return apiFetch<null>('/api/v1/customer/notifications/' + id + '/read?requestId=' + encodeURIComponent(requestId), { method: 'POST' }) },
  async reservations(requestId: string) { requireBff(); return apiFetch<Reservation[]>('/api/v1/customer/reservations?requestId=' + encodeURIComponent(requestId)) },
  async createReservation(body: ReservationCreate) { requireBff(); return apiFetch<Reservation>('/api/v1/customer/reservations', { method: 'POST', body: JSON.stringify(body) }) },
  async cancelReservation(id: number, requestId: string) { requireBff(); return apiFetch<null>('/api/v1/customer/reservations/' + id + '/cancel?requestId=' + encodeURIComponent(requestId), { method: 'POST' }) },
}
