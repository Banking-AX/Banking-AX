import { apiFetch, apiFetchBlob, type ApiEnvelope } from './client'

/**
 * Browser-facing task contract.  Calls are deliberately same-origin: the
 * authenticated BFF derives the actor from the JWT and injects the internal
 * gateway credential server-side. Do not add that credential here.
 */
export const taskBffEnabled = import.meta.env.VITE_BFF_TASKS_ENABLED === 'true'

export type Transaction = { transactionId: number; occurredAt: string; direction: 'IN' | 'OUT'; amount: number; counterparty: string | null; memo: string | null }
export type CustomerAccountOption = { accountId: number; maskedAccountNo: string; productName: string; status: string; version: number | null; balance: number | null }
export type TransactionSearchResult = { accountId: number; accountNoMasked: string; accountName: string; timeZone: string; transactions: Transaction[]; page: number; size: number; totalElements: number; totalPages: number }
export type A01Request = { requestId: string; workItemId: number; accountId: number; startDate: string; endDate: string; transactionType: 'IN' | 'OUT' | null; minimumAmount: string | null; maximumAmount: string | null; keyword: string | null; sortOrder: 'RECENT' | 'OLDEST'; page: number; size: number }

export type B02Prepare = { requestId: string; account: number; waive: boolean; taxed: boolean | null }
export type B02Otp = { requestId: string; version: number; consent: boolean; signed: boolean; phone: string | null; workItemId: number }
export type B02Execute = { requestId: string; version: number; waive: boolean; taxed: boolean | null; binding: string; workItemId: number }
export type B02Assessment = { rate: 'NO_INTEREST' | 'BASE_RATE' | 'PREFERRED_RATE' | 'DOCUMENT_REQUIRED' | 'MANUAL_REVIEW'; rule: string; manual: boolean }
export type B02Execution = { requestId: string; customer: number; account: number; status: string; rate: string; rule: string }

export type C02Heir = { personRef: string; name: string; birthDate: string | null; relationship: string; applicant: boolean; higherOrderOrSubrogation: boolean; visitStatus: 'ATTENDING' | 'ABSENT' | 'UNCONFIRMED_ABSENT'; delegationMethod: 'NONE' | 'DOCUMENT' | 'RECEIPT'; delegationReceiptNo: string | null }
export type C02Prepare = { requestId: string; workItemId: number; registeredPhone: string | null; decedent: { name: string; birthDate: string | null; deathDate: string; depositTotal: number }; heirs: C02Heir[]; documentStatuses: Record<string, string> }
export type C02Prepared = { caseView: { caseId: number; workItemId: number; revision: number; caseStatus: string; reviewStatus: string | null } ; requiredDocuments: C02DocumentSlot[]; submission: { submittable: boolean; errors: string[]; outOfScope: string[]; missingDocuments: string[] } }
export type C02DocumentSlot = { documentTypeCode: string; requirement: 'REQUIRED' | 'CONDITIONAL' | 'VISIT_ONLY'; personRef: string | null; note: string }
export type C02Document = { documentFileId: number; caseId: number; caseRevision: number; documentSlotKey: string; version: number; fileName: string; mimeType: string; sizeBytes: number; status: string; documentType: string; uploadedAt: string }
export type C02ReviewItem = { category?: string; result?: string; message?: string; source?: string; documentFileId?: number }
export type C02Review = { caseId: number; workItemId: number; revision: number; agentRunStatus: string; reviewStatus: string | null; reviewItems: C02ReviewItem[]; error: Record<string, unknown> | null; finalStaffDecision: boolean }
export type C02AiReview = { status: string; code?: string; revision: number; analyzedAt?: string; method: 'LLM' | 'RULE_FALLBACK'; model: string | null; promptVersion: string | null; summary: string; confirmed: string[]; staffChecks: string[]; basis: { docId: string; reason: string }[]; evidence: { docId: string; title: string; section: string }[]; basisMissing: boolean; customerDraft: string }
export type C02OcrDocument = { documentFileId: number; documentVersion: number; method: string; status: string; extracted: string | null; failureReason: string | null; manualVerificationRequired: boolean }
export type C02OcrSnapshot = { caseId: number; revision: number; manualVerificationRequired: boolean; documents: C02OcrDocument[] }
export type C02HeirView = { personRef: string; name: string; birthDate?: string | null; relationship: string; applicant: boolean; visitStatus: string; delegationMethod: string }
export type C02CaseIntake = { decedentName: string; decedentBirthDate: string | null; deathDate: string | null; heirs: C02HeirView[] }
/** ISO instants from service_case; null while a milestone has not happened. Absent when DB persistence is off. */
export type C02CaseTimeline = { createdAt: string | null; submittedAt: string | null; reviewStartedAt: string | null; decidedAt: string | null; resultNotifiedAt: string | null; canceledAt: string | null; updatedAt: string | null }
/** Only returned after the staff result notification was recorded. */
export type C02CustomerDecision = { outcome: 'PROCEED' | 'REQUIREMENT_FAILED' | 'DOCUMENT_INSUFFICIENT'; customerMessage: string }
export type C02CustomerCaseSnapshot = { caseId: number; workItemId: number; revision: number; caseStatus: string; reviewStatus: string | null; visitRequired: boolean; requiredDocuments: C02DocumentSlot[]; documents: C02Document[]; precheck: C02Review; intake: C02CaseIntake | null; timeline: C02CaseTimeline | null; finalDecision: C02CustomerDecision | null }
export type C02StaffDecision = { caseId: number; workItemId: number; customerId: number; staffId: number; revision: number; outcome: 'PROCEED' | 'REQUIREMENT_FAILED' | 'DOCUMENT_INSUFFICIENT'; currentCaseStatus: string; proposedCaseStatus: string; customerMessage: string; notification: { notificationId: string; caseId: number; revision: number; outcome: string; message: string; externalDeliveryAttempted: boolean } }
export type C02StaffSummary = { heirCount: number; absentHeirCount: number; requiredUploadCount: number; uploadedRequiredCount: number; missingRequiredCount: number; visitOnlyCount: number; autoMatchedCount: number; mismatchCount: number; precheckNotRun: boolean; manualVerificationRequired: boolean }
export type C02StaffCase = { caseView: { caseId: number; workItemId: number; revision: number; caseStatus: string; reviewStatus: string | null; visitRequired: boolean }; requiredDocuments: C02DocumentSlot[]; documents: C02Document[]; precheck: C02Review; intake: C02CaseIntake | null; summary: C02StaffSummary; finalDecision: C02StaffDecision | null }
/** Read-only list rows; staff only see non-DRAFT Cases and only with pre-review permission. */
export type C02CaseRow = { caseId: number; workItemId: number; caseStatus: string; reviewStatus: string | null; revision: number; createdAt: string | null; submittedAt: string | null; decidedAt: string | null; resultNotifiedAt: string | null; customerName: string
  /** Staff queue only: staff checks on current files (never OCR matches) and the reviewer who started the review. */
  submittedDocuments?: number; staffConfirmed?: number; staffSupplement?: number; staffUnreviewed?: number
  reviewerId?: number | null; reviewerName?: string | null; reviewStartedAt?: string | null; lastReviewedAt?: string | null }
export type C02DocumentReview = { eventId: number; result: 'CONFIRMED' | 'SUPPLEMENT_REQUIRED'; reason: string | null; staffId: number | null; staffName: string | null; reviewedAt: string }
export type C02DocumentReviewRow = { requiredDocumentId: number; documentTypeCode: string; requirement: string; personRef: string | null; personName: string | null; documentFileId: number | null; fileVersion: number | null; fileName: string | null; uploadedAt: string | null; ocrMethod: string | null; ocrStatus: string | null; review: C02DocumentReview | null }
export type C02DocumentReviewOverview = { caseId: number; caseStatus: string; reviewerId: number | null; reviewerName: string | null; reviewStartedAt: string | null; editable: boolean; documents: C02DocumentReviewRow[]; visitOnly: C02DocumentReviewRow[]; summary: { submitted: number; confirmed: number; supplement: number; unreviewed: number; lastReviewedAt: string | null } }
export type C02DocumentReviewHistory = { eventId: number; documentFileId: number; fileVersion: number; documentTypeCode: string; personName: string | null; previousResult: string | null; result: string; reason: string | null; staffId: number | null; staffName: string | null; reviewedAt: string; currentFile: boolean }
export type C02ReviewTimelineEntry = { at: string; kind: string; actorType: string; actorName: string | null; summary: string; detail: string | null }
export type C02DocumentReviewSave = { fileVersion: number; result: 'CONFIRMED' | 'SUPPLEMENT_REQUIRED'; reason: string | null; expectedReviewId: number | null; requestId: string }
export type Branch = { id: number; name: string; address: string }
export type ReservationAssignment = { assignedAt: string | null; basis: string | null; candidateCount: number; assignedStaffId: number | null; assignedStaffName: string | null; recommendedStaffId: number | null; recommendedStaffName: string | null; assignedWorkload: number; staffChosenByCustomer: boolean
  /** null when the booking predates recording the ranked list; never reconstructed. */
  ranked: { staffId: number; name: string | null; rank: number; activeAssignments: number }[] | null }
export type StaffReservation = { id: number; caseId: number; startsAt: string; status: string; assignedStaffId: number | null; customerName: string; assignedStaffName: string | null; caseType: string
  assignment?: ReservationAssignment | null; events?: { eventType: string; fromValue: string | null; toValue: string | null; actorType: string; at: string }[] }
export type BranchReservations = { branch: Branch; reservations: StaffReservation[] }
export type Reservation = { id: number; caseId: number; branchId: number; staffId: number; startsAt: string; status: string }
/** capacity = active branch staff, booked = staff already holding the time, remaining = free staff now. Booking re-checks on the server. */
export type ReservationSlot = { startsAt: string; available: boolean; capacity?: number; booked?: number; remaining?: number }
/** STAFF-21: one factual question from staff; the answer is the customer's own text. EXPIRED visit questions keep only the provisional UNCONFIRMED_ABSENT. */
export type C02Clarification = { id: number; caseId: number; kind: 'VISIT_ATTENDANCE' | 'DOCUMENT' | 'OTHER'; question: string; status: 'OPEN' | 'ANSWERED' | 'EXPIRED' | 'ESCALATED'; askedAt: string; dueAt: string | null; askedBy: number | null; askedByName: string | null; requestedRevision: number; answer: string | null; answeredAt: string | null; resultRevision: number | null; nonresponseAssumption: string | null }
/** HIST-11: text the customer wrote, deleted 30 days after it was written. */
export type C02Statement = { id: number; caseId: number; revision: number; text: string; writtenAt: string; expiresAt: string }
/** STAFF-19: reference text from the approved rule index; no citations means no evidence was found. */
export type C02PolicyAnswer = { status: 'ANSWERED' | 'INSUFFICIENT' | 'EVIDENCE_ONLY'; answer: string; citations: { docId: string; title: string; section: string; trustLevel: string | null; excerpt: string }[]; method: string; model: string | null; promptVersion: string; retrievedAt: string }
export type ReservationCandidate = { staffId: number; name: string | null; rank: number; activeAssignments: number }
export type ReservationCreate = { requestId: string; caseId: number; branchId: number; staffId: number | null; startsAt: string }

function requireBff() {
  if (!taskBffEnabled) throw new Error('업무 API는 로그인 BFF 연결 후 사용할 수 있습니다.')
}

/** BFF must add customerId from session; the browser never sends actor headers. */
export async function searchTransactions(request: A01Request): Promise<ApiEnvelope<TransactionSearchResult>> {
  requireBff()
  return apiFetch<TransactionSearchResult>('/api/v1/customer/a01/transaction-search', { method: 'POST', body: JSON.stringify(request) })
}

export async function getA01Accounts(requestId: string): Promise<ApiEnvelope<CustomerAccountOption[]>> {
  requireBff()
  return apiFetch<CustomerAccountOption[]>('/api/v1/customer/accounts/a01?requestId=' + encodeURIComponent(requestId))
}

export const b02Api = {
  async accounts(requestId: string) { requireBff(); return apiFetch<CustomerAccountOption[]>('/api/v1/customer/accounts/b02?requestId=' + encodeURIComponent(requestId)) },
  async prepare(body: B02Prepare) { requireBff(); return apiFetch<B02Assessment>('/api/v1/customer/b02/prepare', { method: 'POST', body: JSON.stringify(body) }) },
  async issueOtp(accountId: number, body: B02Otp) { requireBff(); return apiFetch<{ challengeId: string }>('/api/v1/customer/b02/' + accountId + '/otp', { method: 'POST', body: JSON.stringify(body) }) },
  async verifyOtp(challenge: string, otp: string, requestId: string) { requireBff(); return apiFetch<{ bindingId: string }>('/api/v1/customer/b02/otp/verify', { method: 'POST', body: JSON.stringify({ requestId, challenge, otp }) }) },
  async execute(accountId: number, body: B02Execute) { requireBff(); return apiFetch<B02Execution>('/api/v1/customer/b02/' + accountId + '/execute', { method: 'POST', body: JSON.stringify(body) }) },
}

export const c02Api = {
  async startWorkItem(requestId: string) { requireBff(); return apiFetch<{ workflowId: number; workItemId: number; taskCode: string; status: string }>('/api/v1/customer/c02/work-items', { method: 'POST', body: JSON.stringify({ requestId }) }) },
  async resume(workItemId: number, requestId: string) { requireBff(); return apiFetch<C02Prepared>('/api/v1/customer/c02/work-items/' + workItemId + '/resume?requestId=' + encodeURIComponent(requestId)) },
  async prepare(body: C02Prepare) { requireBff(); return apiFetch<C02Prepared>('/api/v1/customer/c02/prepare', { method: 'POST', body: JSON.stringify(body) }) },
  async consent(caseId: number, body: { revision: number; requestId: string; consented: boolean; mockSignatureCompleted: boolean }) { requireBff(); return apiFetch<{ caseId: number; revision: number }>('/api/v1/customer/c02/' + caseId + '/consent', { method: 'POST', body: JSON.stringify(body) }) },
  async issueVerification(caseId: number, body: { revision: number; requestId: string }) { requireBff(); return apiFetch<{ challengeId: string; maskedPhone: string; expiresAt: string }>('/api/v1/customer/c02/' + caseId + '/verification/issue', { method: 'POST', body: JSON.stringify(body) }) },
  async verifyVerification(body: { requestId: string; challengeId: string; otp: string }) { requireBff(); return apiFetch<{ bindingId: string }>('/api/v1/customer/c02/verification/verify', { method: 'POST', body: JSON.stringify(body) }) },
  async submit(caseId: number, body: { revision: number; requestId: string; bindingId: string }) { requireBff(); return apiFetch<{ caseId: number; workItemId: number; revision: number; caseStatus: string; agentRunStatus: string; reviewStatus: string | null }>('/api/v1/customer/c02/' + caseId + '/submit', { method: 'POST', body: JSON.stringify(body) }) },
  async uploadDocument(caseId: number, input: { revision: number; requestId: string; documentSlotKey: string; file: File }) { requireBff(); const form = new FormData(); form.set('revision', String(input.revision)); form.set('requestId', input.requestId); form.set('documentSlotKey', input.documentSlotKey); form.set('file', input.file); return apiFetch<{ revision: number; document: C02Document }>('/api/v1/customer/c02/' + caseId + '/documents/upload', { method: 'POST', body: form }) },
  async powerOfAttorneyDraft(caseId: number, body: { delegatorPersonRef: string; accountNumbers: string[]; depositProduct: string; agentAddress: string; delegatorAddress: string; delegatorRelationship: string; writtenDate: string | null }) { requireBff(); return apiFetchBlob('/api/v1/customer/c02/' + caseId + '/power-of-attorney/draft', { method: 'POST', body: JSON.stringify(body) }) },
  async documents(caseId: number, requestId: string) { requireBff(); return apiFetch<C02Document[]>('/api/v1/customer/c02/' + caseId + '/documents?requestId=' + encodeURIComponent(requestId)) },
  async customerOcr(caseId: number, revision: number, requestId: string) { requireBff(); return apiFetch<C02OcrSnapshot>('/api/v1/customer/c02/' + caseId + '/ocr?revision=' + revision + '&requestId=' + encodeURIComponent(requestId)) },
  async review(caseId: number, requestId: string) { requireBff(); return apiFetch<C02Review>('/api/v1/customer/c02/' + caseId + '/review?requestId=' + encodeURIComponent(requestId)) },
  async snapshot(caseId: number, requestId: string) { requireBff(); return apiFetch<C02CustomerCaseSnapshot>('/api/v1/customer/c02/' + caseId + '/snapshot?requestId=' + encodeURIComponent(requestId)) },
  async staffCase(caseId: number, requestId: string) { requireBff(); return apiFetch<C02StaffCase>('/api/v1/staff/c02/' + caseId + '/staff-case?requestId=' + encodeURIComponent(requestId)) },
  async aiReview(caseId: number, requestId: string) { requireBff(); return apiFetch<C02AiReview | null>('/api/v1/staff/c02/' + caseId + '/ai-review?requestId=' + encodeURIComponent(requestId)) },
  async generateAiReview(caseId: number, body: { revision: number; requestId: string }) { requireBff(); return apiFetch<C02AiReview>('/api/v1/staff/c02/' + caseId + '/ai-review', { method: 'POST', body: JSON.stringify(body) }) },
  async staffOcr(caseId: number, revision: number, requestId: string) { requireBff(); return apiFetch<C02OcrSnapshot>('/api/v1/staff/c02/' + caseId + '/ocr?revision=' + revision + '&requestId=' + encodeURIComponent(requestId)) },
  async staffPrecheck(caseId: number, body: { revision: number; requestId: string }) { requireBff(); return apiFetch<C02Review>('/api/v1/staff/c02/' + caseId + '/staff-precheck', { method: 'POST', body: JSON.stringify(body) }) },
  async staffDecision(caseId: number, body: { revision: number; requestId: string; outcome: 'PROCEED' | 'REQUIREMENT_FAILED' | 'DOCUMENT_INSUFFICIENT'; customerMessage: string; exceptionReason?: string | null }) { requireBff(); return apiFetch<C02StaffDecision>('/api/v1/staff/c02/' + caseId + '/staff-decision', { method: 'POST', body: JSON.stringify(body) }) },
  async customerCases(requestId: string) { requireBff(); return apiFetch<C02CaseRow[]>('/api/v1/customer/c02/cases?requestId=' + encodeURIComponent(requestId)) },
  async startReview(caseId: number, body: { revision: number; requestId: string }) { requireBff(); return apiFetch<{ caseId: number; revision: number; caseStatus: string }>('/api/v1/staff/c02/' + caseId + '/start-review', { method: 'POST', body: JSON.stringify(body) }) },
  async cancelCase(caseId: number, requestId: string) { requireBff(); return apiFetch<{ caseId: number; revision: number; caseStatus: string }>('/api/v1/customer/c02/' + caseId + '/cancel', { method: 'POST', body: JSON.stringify({ requestId }) }) },
  async documentReviews(caseId: number, requestId: string) { requireBff(); return apiFetch<C02DocumentReviewOverview>('/api/v1/staff/c02/' + caseId + '/document-reviews?requestId=' + encodeURIComponent(requestId)) },
  async reviewTimeline(caseId: number, requestId: string) { requireBff(); return apiFetch<C02ReviewTimelineEntry[]>('/api/v1/staff/c02/' + caseId + '/review-timeline?requestId=' + encodeURIComponent(requestId)) },
  async documentReviewHistory(caseId: number, requestId: string) { requireBff(); return apiFetch<C02DocumentReviewHistory[]>('/api/v1/staff/c02/' + caseId + '/document-reviews/history?requestId=' + encodeURIComponent(requestId)) },
  async reviewDocument(caseId: number, documentFileId: number, body: C02DocumentReviewSave) { requireBff(); return apiFetch<C02DocumentReviewRow>('/api/v1/staff/c02/' + caseId + '/documents/' + documentFileId + '/review', { method: 'PUT', body: JSON.stringify(body) }) },
  async customerDocument(caseId: number, documentId: number) { requireBff(); return apiFetchBlob('/api/v1/customer/c02/' + caseId + '/documents/' + documentId + '/content') },
  async staffDocument(caseId: number, documentId: number) { requireBff(); return apiFetchBlob('/api/v1/staff/c02/' + caseId + '/documents/' + documentId + '/content') },
  async staffCases(requestId: string) { requireBff(); return apiFetch<C02CaseRow[]>('/api/v1/staff/c02/cases?requestId=' + encodeURIComponent(requestId)) },
  async branches(requestId: string) { requireBff(); return apiFetch<Branch[]>('/api/v1/customer/reservations/branches?requestId=' + encodeURIComponent(requestId)) },
  async staffReservations(requestId: string) { requireBff(); return apiFetch<BranchReservations>('/api/v1/staff/reservations?requestId=' + encodeURIComponent(requestId)) },
  async reservationAvailability(branchId: number, date: string, requestId: string) { requireBff(); return apiFetch<ReservationSlot[]>('/api/v1/customer/reservations/availability?branchId=' + branchId + '&date=' + date + '&requestId=' + encodeURIComponent(requestId)) },
  async reservations(requestId: string) { requireBff(); return apiFetch<Reservation[]>('/api/v1/customer/reservations?requestId=' + encodeURIComponent(requestId)) },
  async createReservation(body: ReservationCreate) { requireBff(); return apiFetch<Reservation>('/api/v1/customer/reservations', { method: 'POST', body: JSON.stringify(body) }) },
  async changeReservation(id: number, body: ReservationCreate) { requireBff(); return apiFetch<Reservation>('/api/v1/customer/reservations/' + id + '/change', { method: 'POST', body: JSON.stringify(body) }) },
  async reservationCandidates(id: number, requestId: string) { requireBff(); return apiFetch<ReservationCandidate[]>('/api/v1/staff/reservations/' + id + '/candidates?requestId=' + encodeURIComponent(requestId)) },
  async assignReservation(id: number, body: { staffId: number; requestId: string }) { requireBff(); return apiFetch<Reservation>('/api/v1/staff/reservations/' + id + '/assign', { method: 'POST', body: JSON.stringify(body) }) },
  async askPolicyQuestion(caseId: number, body: { question: string; requestId: string }) { requireBff(); return apiFetch<C02PolicyAnswer>('/api/v1/staff/c02/' + caseId + '/policy-question', { method: 'POST', body: JSON.stringify(body) }) },
  async staffClarifications(caseId: number, requestId: string) { requireBff(); return apiFetch<C02Clarification[]>('/api/v1/staff/c02/' + caseId + '/clarifications?requestId=' + encodeURIComponent(requestId)) },
  async askClarification(caseId: number, body: { kind: C02Clarification['kind']; question: string; requestId: string }) { requireBff(); return apiFetch<C02Clarification>('/api/v1/staff/c02/' + caseId + '/clarifications', { method: 'POST', body: JSON.stringify(body) }) },
  async customerClarifications(caseId: number, requestId: string) { requireBff(); return apiFetch<C02Clarification[]>('/api/v1/customer/c02/' + caseId + '/clarifications?requestId=' + encodeURIComponent(requestId)) },
  async answerClarification(caseId: number, id: number, body: { answer: string; requestId: string }) { requireBff(); return apiFetch<C02Clarification>('/api/v1/customer/c02/' + caseId + '/clarifications/' + id + '/answer', { method: 'POST', body: JSON.stringify(body) }) },
  async customerStatements(caseId: number, requestId: string) { requireBff(); return apiFetch<C02Statement[]>('/api/v1/customer/c02/' + caseId + '/statements?requestId=' + encodeURIComponent(requestId)) },
  async writeStatement(caseId: number, body: { text: string; requestId: string }) { requireBff(); return apiFetch<C02Statement>('/api/v1/customer/c02/' + caseId + '/statements', { method: 'POST', body: JSON.stringify(body) }) },
  async staffStatements(caseId: number, requestId: string) { requireBff(); return apiFetch<C02Statement[]>('/api/v1/staff/c02/' + caseId + '/statements?requestId=' + encodeURIComponent(requestId)) },
  async cancelReservation(id: number, requestId: string) { requireBff(); return apiFetch<null>('/api/v1/customer/reservations/' + id + '/cancel?requestId=' + encodeURIComponent(requestId), { method: 'POST' }) },
}
