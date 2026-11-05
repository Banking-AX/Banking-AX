import { ApiError, apiFetch, type ApiEnvelope } from './client'
import type { SignAdapter } from './sign'

export const B01_NOTICE_VERSION = 'B01_NOTICE_V1'
export type RepaymentOrder = 'INTEREST_FIRST' | 'PRINCIPAL_FIRST'
export type B01Loan = { loanId: string; loanName: string; loanNoMasked: string; balance: number | string; overdueDays: number; accelerationDeclared: boolean; accelerationDate?: string; repaymentOrder: RepaymentOrder }
export type B01RuleCode = 'ELIGIBLE' | 'NOT_ACCELERATED' | 'OUTSOURCED_LOAN' | 'AGREEMENT_LOAN' | 'ALREADY_APPLIED'
export type B01MismatchCode = 'OCR_NOT_AVAILABLE' | 'OCR_NOT_SUCCEEDED' | 'OCR_RESULT_INVALID' | 'OCR_LOAN_COUNT_INVALID' | 'ACCOUNT_NUMBER_MISMATCH' | 'BALANCE_MISMATCH' | 'ACCELERATION_DATE_MISMATCH' | 'REQUESTED_ORDER_MISMATCH'
export type B01ImageStatus = 'MATCHED' | 'REQUIRES_RESUBMISSION' | 'UNCERTAIN' | 'UNAVAILABLE' | 'NOT_AVAILABLE'
export type B01ImageMismatchCode = 'IMAGE_ANALYSIS_NOT_AVAILABLE' | 'IMAGE_ANALYSIS_UNAVAILABLE' | 'ALIGNMENT_FAILED' | 'IMAGE_ANALYSIS_UNCERTAIN' | 'CHECKBOX_NOT_SELECTED' | 'MULTIPLE_CHECKBOXES' | 'REQUESTED_ORDER_MISMATCH' | 'DEBTOR_SIGNATURE_TRACE_NOT_FOUND'
export type B01VisualChecks = { alignmentStatus: string; firstBox: string; secondBox: string; selectedOrder: string; debtorSignatureTrace: string; uncertain: boolean; manualVerificationRequired: boolean; failureReason: string | null }
export type B01ImageCheck = { matched: boolean; status: B01ImageStatus; mismatchCodes: B01ImageMismatchCode[] }
export type B01Eligibility = { ruleCode: B01RuleCode; loanRuleEligible: boolean; eligible: boolean; thirdPartyProvider: boolean; additionalConfirmationRequired: boolean; ocrMatched: boolean; mismatchCodes: B01MismatchCode[]; image: B01ImageCheck; documentFileId: number | null; signaturesVerified: boolean; authenticityVerified: boolean; manualVerificationRequired: boolean; automaticProcessingAllowed: boolean }
export type B01Ocr = { method: string; status: string; loans: { maskedAccountNumber: string; balance: number; accelerationDate?: string }[]; requestedRepaymentOrder: RepaymentOrder | null; visualChecks: B01VisualChecks | null; authenticityVerified: boolean; manualVerificationRequired: boolean }
export type B01Document = { documentFileId: number; version: number; displayName: string; sizeBytes: number; contentType: string; uploadedAt: string; ocr: B01Ocr | null }
export type B01Step = 'LOAN_SELECTION' | 'APPLICATION_UPLOAD' | 'ELIGIBILITY_VALIDATION' | 'ELIGIBILITY_REVIEW' | 'CONSENT' | 'MOCK_SIGNATURE' | 'SMS_VERIFICATION' | 'READY_TO_EXECUTE'
export type B01Context = { workItemId: number; workItemVersion: number; workItemStatus: 'ACTIVE' | 'ON_HOLD'; resumeStep: string | null; selectedLoanId: string | null; requestedRepaymentOrder: RepaymentOrder | null; applicationDocument: B01Document | null; lastEligibility: B01Eligibility | null; consentAgreed: boolean; mockSignatureCompleted: boolean; smsVerified: boolean; currentStep: B01Step; readyToExecute: boolean }
export type WorkItemResumeResult = { workItemId: number; workflowId: number; taskCode: string; status: 'ACTIVE'; version: number; resumeStep: string | null }
export type B01UploadResult = { documentFileId: number; version: number; documentTypeCode: string; detectedType: string | null; method: string; status: string; fields: { loans: { maskedAccountNumber: string; balance: number; accelerationDate?: string }[]; requestedRepaymentOrder: RepaymentOrder | null }; signatureChecks: { debtorSignatureField: string; thirdPartySignatureField: string }; visualChecks: B01VisualChecks | null; authenticityVerified: boolean; manualVerificationRequired: boolean }
export type B01Result = { status: 'DONE' | 'PENDING'; processedAt?: string; consentAt?: string; maskedEmail?: string }

export interface B01Adapter {
  requiresApplicationDocument: boolean
  listLoans(): Promise<B01Loan[]>
  getContext(): Promise<B01Context>
  resume(expectedVersion: number): Promise<WorkItemResumeResult>
  saveSelection(loanId: string, requestedOrder: RepaymentOrder): Promise<B01Context>
  uploadApplication(file: File): Promise<B01UploadResult>
  checkEligibility(loanId: string, requestedOrder: RepaymentOrder): Promise<B01Eligibility>
  recordConsent(input: { loanId: string; requestedOrder: RepaymentOrder; documentFileId: number }): Promise<void>
  sign: SignAdapter
  finalize(): Promise<B01Result>
}

type ServerLoan = Omit<B01Loan, 'loanId' | 'loanNoMasked' | 'repaymentOrder'> & { loanId: number; maskedAccountNumber: string; currentRepaymentOrder: RepaymentOrder }
export type ServerEligibility = { ruleCode: B01RuleCode; loanRuleEligible: boolean; thirdPartyProvider: boolean; additionalConfirmationRequired: boolean; ocr: { matched: boolean; mismatchCodes: B01MismatchCode[]; documentFileId: number | null }; image?: B01ImageCheck | null; verification: { signaturesVerified: boolean; authenticityVerified: boolean; manualVerificationRequired: boolean }; automaticProcessingAllowed: boolean }
type ServerContext = Omit<B01Context, 'selectedLoanId' | 'lastEligibility'> & { selectedLoanId: number | null; lastEligibility: ServerEligibility | null }

function data<T>(envelope: ApiEnvelope<T>): T {
  if (envelope.data === null) throw new ApiError('INVALID_RESPONSE', 200, 'The response has no data.')
  return envelope.data
}
function newRequestId(workItemId: string, action: string): string {
  return `b01-${workItemId}-${action.slice(0, 12)}-${crypto.randomUUID()}`.slice(0, 64)
}
export function mapB01Eligibility(value: ServerEligibility): B01Eligibility {
  const image = value.image ?? { matched: false, status: 'NOT_AVAILABLE', mismatchCodes: ['IMAGE_ANALYSIS_NOT_AVAILABLE'] }
  return { ruleCode: value.ruleCode, loanRuleEligible: value.loanRuleEligible, eligible: value.loanRuleEligible && value.ocr.matched && image.matched, thirdPartyProvider: value.thirdPartyProvider, additionalConfirmationRequired: value.additionalConfirmationRequired, ocrMatched: value.ocr.matched, mismatchCodes: value.ocr.mismatchCodes, image, documentFileId: value.ocr.documentFileId, signaturesVerified: value.verification.signaturesVerified, authenticityVerified: value.verification.authenticityVerified, manualVerificationRequired: value.verification.manualVerificationRequired, automaticProcessingAllowed: value.automaticProcessingAllowed }
}
function context(value: ServerContext): B01Context {
  return { ...value, selectedLoanId: value.selectedLoanId === null ? null : String(value.selectedLoanId), lastEligibility: value.lastEligibility ? mapB01Eligibility(value.lastEligibility) : null }
}

/** Real Spring BFF adapter. Request IDs are retained for safe retries during this page session. */
export function createRealB01Adapter(workItemId: string): B01Adapter {
  const base = `/api/v1/customer/b01/work-items/${encodeURIComponent(workItemId)}`
  const stable = new Map<string, string>()
  const id = (key: string) => {
    const previous = stable.get(key)
    if (previous) return previous
    const created = newRequestId(workItemId, key)
    stable.set(key, created)
    return created
  }
  const sign: SignAdapter = {
    async submitSignature(submission) {
      const form = new FormData()
      form.append('file', await submission.toPngBlob(), 'mock-signature.png')
      await apiFetch(`${base}/mock-signature?requestId=${encodeURIComponent(id('signature'))}`, { method: 'POST', body: form })
    },
    async issueCode() {
      const issueId = newRequestId(workItemId, 'sms-issue')
      return data(await apiFetch<{ challengeId: string; maskedPhone: string; expiresAt: string }>(`${base}/sms/requests`, { method: 'POST', body: JSON.stringify({ requestId: issueId }) }))
    },
    async verifyCode(challengeId, otp) {
      const value = data(await apiFetch<{ verified: boolean }>(`${base}/sms/verifications`, { method: 'POST', body: JSON.stringify({ requestId: id(`sms-verify-${challengeId}`), challengeId, otp }) }))
      if (!value.verified) throw new ApiError('OTP_INVALID', 400, 'SMS verification did not complete.')
      return {}
    },
    async complete() {
      const value = data(await apiFetch<ServerContext>(`${base}/context`))
      if (!value.readyToExecute) throw new ApiError('INVALID_STATE', 409, 'Verification is not complete.')
    },
  }
  return {
    requiresApplicationDocument: true,
    async listLoans() {
      return data(await apiFetch<ServerLoan[]>(`${base}/loans`)).map(item => ({ loanId: String(item.loanId), loanName: item.loanName, loanNoMasked: item.maskedAccountNumber, balance: item.balance, overdueDays: item.overdueDays, accelerationDeclared: item.accelerationDeclared, accelerationDate: item.accelerationDate, repaymentOrder: item.currentRepaymentOrder }))
    },
    async getContext() { return context(data(await apiFetch<ServerContext>(`${base}/context`))) },
    async resume(expectedVersion) {
      return data(await apiFetch<WorkItemResumeResult>(
        `/api/v1/customer/work-items/${encodeURIComponent(workItemId)}/resume`,
        { method: 'POST', body: JSON.stringify({ expectedVersion }) },
      ))
    },
    async saveSelection(loanId, requestedOrder) {
      return context(data(await apiFetch<ServerContext>(`${base}/context`, { method: 'PUT', body: JSON.stringify({ requestId: id(`selection-${loanId}-${requestedOrder}`), loanId: Number(loanId), requestedRepaymentOrder: requestedOrder }) })))
    },
    async uploadApplication(file) {
      const form = new FormData(); form.append('file', file)
      return data(await apiFetch<B01UploadResult>(`${base}/documents/application?requestId=${encodeURIComponent(id(`upload-${file.name}-${file.size}-${file.lastModified}`))}`, { method: 'POST', body: form }))
    },
    async checkEligibility(loanId, requestedOrder) {
      return mapB01Eligibility(data(await apiFetch<ServerEligibility>(`${base}/eligibility`, { method: 'POST', body: JSON.stringify({ requestId: id(`eligibility-${loanId}-${requestedOrder}`), loanId: Number(loanId), requestedRepaymentOrder: requestedOrder }) })))
    },
    async recordConsent(input) {
      await apiFetch(`${base}/consent`, { method: 'POST', body: JSON.stringify({ requestId: id(`consent-${input.loanId}-${input.requestedOrder}-${input.documentFileId}`), loanId: Number(input.loanId), requestedRepaymentOrder: input.requestedOrder, documentFileId: input.documentFileId, noticeVersion: B01_NOTICE_VERSION, agreed: true }) })
    },
    sign,
    async finalize() {
      const value = data(await apiFetch<{ processedAt: string; consentAt: string | null }>(`${base}/execute`, {
        method: 'POST', body: JSON.stringify({ requestId: id('execute') }),
      }))
      return { status: 'DONE', processedAt: value.processedAt, consentAt: value.consentAt ?? undefined }
    },
  }
}
