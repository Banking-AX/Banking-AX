import { apiFetch, type ApiEnvelope } from './client'

/**
 * Browser-facing task contract.  Calls are deliberately same-origin: the
 * authenticated BFF derives the actor from the JWT and injects the internal
 * gateway credential server-side. Do not add that credential here.
 */
export const taskBffEnabled = import.meta.env.VITE_BFF_TASKS_ENABLED === 'true'
// OTP issuance may send an SMS. Enable this only when the backend uses its test-only mock sender.
export const c02MockSmsEnabled = import.meta.env.VITE_C02_SMS_MODE === 'mock'

export type Transaction = { transactionId: number; occurredAt: string; direction: 'IN' | 'OUT'; amount: number; counterparty: string | null; memo: string | null }
export type TransactionSearchResult = { accountId: number; accountNoMasked: string; accountName: string; timeZone: string; transactions: Transaction[]; page: number; size: number; totalElements: number; totalPages: number }
export type A01Request = { requestId: string; workItemId: number; accountId: number; startDate: string; endDate: string; transactionType: 'IN' | 'OUT' | null; minimumAmount: string | null; maximumAmount: string | null; keyword: string | null; sortOrder: 'RECENT' | 'OLDEST'; page: number; size: number }

export type B02Prepare = { requestId: string; account: number; waive: boolean; taxed: boolean | null }
export type B02PrepareResult = { rate: string; rule: string; manual: boolean }
export type B02ExecuteResult = { requestId: string; customer: number; account: number; status: string; rate: string; rule: string; command?: unknown }
export type B02Otp = { requestId: string; version: number; consent: boolean; signed: boolean; phone: string | null; workItemId: number }
export type B02Execute = { requestId: string; version: number; waive: boolean; taxed: boolean | null; binding: string; workItemId: number }

export type C02Heir = { personRef: string; name: string; birthDate: string | null; relationship: string; applicant: boolean; higherOrderOrSubrogation: boolean; visitStatus: 'ATTENDING' | 'ABSENT' | 'UNCONFIRMED_ABSENT'; delegationMethod: 'NONE' | 'DOCUMENT' | 'RECEIPT'; delegationReceiptNo: string | null }
export type C02Prepare = { requestId: string; workItemId: number; registeredPhone: string | null; decedent: { name: string; birthDate: string | null; deathDate: string; depositTotal: number }; heirs: C02Heir[]; documentStatuses: Record<string, string> }
export type C02DocumentSlot = { documentTypeCode: string; personRef: string | null; requirement: 'REQUIRED' | 'CONDITIONAL' | 'VISIT_ONLY'; note: string }
export type C02Document = { documentFileId: number; caseId: number; caseRevision: number; documentSlotKey: string; version: number; fileName: string; mimeType: string; sizeBytes: number; status: string; documentType: string; uploadedAt: string }
export type C02OcrDocument = { documentFileId: number; caseRevision: number; documentTypeCode: string; personRef: string | null; method: 'NONE' | 'MOCK' | 'CLOVA_GENERAL' | 'CLOVA_TEMPLATE'; status: 'PENDING' | 'NOT_APPLICABLE' | 'SUCCEEDED' | 'FAILED_NEEDS_STAFF'; extracted: Record<string, unknown> | null; failureReason: string | null; manualVerificationRequired: true }
export type C02OcrSnapshot = { caseId: number; revision: number; manualVerificationRequired: true; documents: C02OcrDocument[] }
export type C02Review = { caseId: number; workItemId: number; revision: number; agentRunStatus: string; reviewStatus: string | null; reviewItems: { category?: string; message?: string; source?: string; documentFileId?: number }[]; error: Record<string, unknown> | null; finalStaffDecision: boolean }
export type C02CustomerCaseSnapshot = { caseId: number; workItemId: number; revision: number; caseStatus: string; reviewStatus: string | null; visitRequired: boolean; requiredDocuments: C02DocumentSlot[]; documents: C02Document[]; precheck: C02Review }
export type C02Prepared = { caseView: { caseId: number; workItemId: number; revision: number; caseStatus: string; reviewStatus: string | null; visitRequired: boolean } | null; requiredDocuments: C02DocumentSlot[]; submission: { submittable: boolean; errors: string[]; outOfScope: string[]; missingDocuments: string[] } }
export type C02Submission = { customerId: number; caseId: number; workItemId: number; revision: number; caseStatus: string; agentRunStatus: string; reviewStatus: string; reviewItems: C02Review['reviewItems']; error: Record<string, unknown> | null }
export type C02OtpIssue = { challengeId: string; maskedPhone: string; expiresAt: string }
export type C02OtpBinding = { bindingId: string; customerId: number; workItemId: number; action: string; requestVersion: number; expiresAt: string }
export type C02StaffDecision = { caseId: number; workItemId: number; customerId: number; staffId: number; revision: number; outcome: 'PROCEED' | 'REQUIREMENT_FAILED' | 'DOCUMENT_INSUFFICIENT'; currentCaseStatus: string; proposedCaseStatus: string; customerMessage: string; notification: { notificationId: string; caseId: number; revision: number; outcome: string; message: string; externalDeliveryAttempted: boolean } }
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
  async prepare(body: B02Prepare) { requireBff(); return apiFetch<B02PrepareResult>('/api/v1/customer/b02/prepare', { method: 'POST', body: JSON.stringify(body) }) },
  async issueOtp(accountId: number, body: B02Otp) { requireBff(); return apiFetch<{ challengeId: string }>('/api/v1/customer/b02/' + accountId + '/otp', { method: 'POST', body: JSON.stringify(body) }) },
  async verifyOtp(challenge: string, otp: string, requestId: string) { requireBff(); return apiFetch<{ bindingId: string }>('/api/v1/customer/b02/otp/verify', { method: 'POST', body: JSON.stringify({ requestId, challenge, otp }) }) },
  async execute(accountId: number, body: B02Execute) { requireBff(); return apiFetch<B02ExecuteResult>('/api/v1/customer/b02/' + accountId + '/execute', { method: 'POST', body: JSON.stringify(body) }) },
}

export const c02Api = {
  async prepare(body: C02Prepare) { requireBff(); return apiFetch<C02Prepared>('/api/v1/customer/c02/prepare', { method: 'POST', body: JSON.stringify(body) }) },
  async uploadDocument(caseId: number, revision: number, requestId: string, documentSlotKey: string, file: File) {
    requireBff()
    const body = new FormData()
    body.set('revision', String(revision)); body.set('requestId', requestId)
    body.set('documentSlotKey', documentSlotKey); body.set('file', file)
    return apiFetch<{ document: C02Document; revision: number; reviewStatus: string | null; precheck: C02Review | null }>(`/api/v1/customer/c02/${caseId}/documents/upload`, { method: 'POST', body })
  },
  async documents(caseId: number, requestId: string) { requireBff(); return apiFetch<C02Document[]>(`/api/v1/customer/c02/${caseId}/documents?requestId=${encodeURIComponent(requestId)}`) },
  async customerOcr(caseId: number, revision: number, requestId: string) { requireBff(); return apiFetch<C02OcrSnapshot>(`/api/v1/customer/c02/${caseId}/ocr?revision=${revision}&requestId=${encodeURIComponent(requestId)}`) },
  async review(caseId: number, requestId: string) { requireBff(); return apiFetch<C02Review>(`/api/v1/customer/c02/${caseId}/review?requestId=${encodeURIComponent(requestId)}`) },
  async snapshot(caseId: number, requestId: string) { requireBff(); return apiFetch<C02CustomerCaseSnapshot>(`/api/v1/customer/c02/${caseId}/snapshot?requestId=${encodeURIComponent(requestId)}`) },
  async recordConsent(caseId: number, body: { revision: number; requestId: string; consented: boolean; mockSignatureCompleted: boolean }) { requireBff(); return apiFetch<unknown>(`/api/v1/customer/c02/${caseId}/consent`, { method: 'POST', body: JSON.stringify(body) }) },
  async issueOtp(caseId: number, body: { revision: number; requestId: string }) { requireBff(); return apiFetch<C02OtpIssue>(`/api/v1/customer/c02/${caseId}/verification/issue`, { method: 'POST', body: JSON.stringify(body) }) },
  async verifyOtp(body: { requestId: string; challengeId: string; otp: string }) { requireBff(); return apiFetch<C02OtpBinding>('/api/v1/customer/c02/verification/verify', { method: 'POST', body: JSON.stringify(body) }) },
  async submit(caseId: number, body: { revision: number; requestId: string; bindingId: string }) { requireBff(); return apiFetch<C02Submission>(`/api/v1/customer/c02/${caseId}/submit`, { method: 'POST', body: JSON.stringify(body) }) },
  async staffCase(caseId: number, requestId: string) { requireBff(); return apiFetch<unknown>('/api/v1/staff/c02/' + caseId + '/staff-case?requestId=' + encodeURIComponent(requestId)) },
  async staffOcr(caseId: number, revision: number, requestId: string) { requireBff(); return apiFetch<C02OcrSnapshot>('/api/v1/staff/c02/' + caseId + '/ocr?revision=' + revision + '&requestId=' + encodeURIComponent(requestId)) },
  async staffPrecheck(caseId: number, body: { revision: number; requestId: string }) { requireBff(); return apiFetch<C02Review>('/api/v1/staff/c02/' + caseId + '/staff-precheck', { method: 'POST', body: JSON.stringify(body) }) },
  async staffReview(caseId: number, requestId: string) { requireBff(); return apiFetch<C02Review>('/api/v1/staff/c02/' + caseId + '/staff-review?requestId=' + encodeURIComponent(requestId)) },
  async staffDecision(caseId: number, body: { revision: number; requestId: string; outcome: 'PROCEED' | 'REQUIREMENT_FAILED' | 'DOCUMENT_INSUFFICIENT'; customerMessage: string }) { requireBff(); return apiFetch<C02StaffDecision>('/api/v1/staff/c02/' + caseId + '/staff-decision', { method: 'POST', body: JSON.stringify(body) }) },
  async notifications(requestId: string) { requireBff(); return apiFetch<NotificationItem[]>('/api/v1/customer/notifications?requestId=' + encodeURIComponent(requestId)) },
  async readNotification(id: number, requestId: string) { requireBff(); return apiFetch<null>('/api/v1/customer/notifications/' + id + '/read?requestId=' + encodeURIComponent(requestId), { method: 'POST' }) },
  async reservations(requestId: string) { requireBff(); return apiFetch<Reservation[]>('/api/v1/customer/reservations?requestId=' + encodeURIComponent(requestId)) },
  async createReservation(body: ReservationCreate) { requireBff(); return apiFetch<Reservation>('/api/v1/customer/reservations', { method: 'POST', body: JSON.stringify(body) }) },
  async cancelReservation(id: number, requestId: string) { requireBff(); return apiFetch<null>('/api/v1/customer/reservations/' + id + '/cancel?requestId=' + encodeURIComponent(requestId), { method: 'POST' }) },
}
