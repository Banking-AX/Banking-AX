import { ApiError } from '../client'
import type { B01Adapter, B01Context, B01Eligibility, B01Loan, B01UploadResult, RepaymentOrder } from '../b01'
import { createMockSignAdapter } from './signMock'

// Mock only: loan data and rulings copied from docs/design-ref/B01Page.dc.html.
const MOCK_LOANS: B01Loan[] = [
  { loanId: 'l1', loanName: 'AX 개인 신용대출', loanNoMasked: '210-***-557731', balance: 17200000, overdueDays: 45, accelerationDeclared: true, accelerationDate: '2026-09-30', repaymentOrder: 'INTEREST_FIRST' },
  { loanId: 'l2', loanName: 'AX 전세자금대출', loanNoMasked: '230-***-114820', balance: 120000000, overdueDays: 0, accelerationDeclared: false, repaymentOrder: 'INTEREST_FIRST' },
]
// The design marks a joint guarantor only on the first loan.
const THIRD_PARTY_LOANS = new Set(['l1'])
const ALLOWED_TYPES = new Set(['application/pdf', 'image/png', 'image/jpeg'])

const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

export function createMockB01Adapter(): B01Adapter {
  const sign = createMockSignAdapter('b01')
  let consentAt: string | null = null
  let selection: { loanId: string; requestedOrder: RepaymentOrder } | null = null
  let document: B01UploadResult | null = null
  let lastEligibility: B01Eligibility | null = null

  const currentContext = (): B01Context => ({
    workItemId: 1, workItemVersion: 0, workItemStatus: 'ACTIVE', resumeStep: null,
    selectedLoanId: selection?.loanId ?? null, requestedRepaymentOrder: selection?.requestedOrder ?? null,
    applicationDocument: document ? {
      documentFileId: document.documentFileId, version: document.version, displayName: 'mock-application.pdf',
      sizeBytes: 100, contentType: 'application/pdf', uploadedAt: new Date().toISOString(),
      ocr: { method: document.method, status: document.status, loans: document.fields.loans,
        requestedRepaymentOrder: document.fields.requestedRepaymentOrder,
        visualChecks: document.visualChecks,
        authenticityVerified: document.authenticityVerified, manualVerificationRequired: document.manualVerificationRequired },
    } : null,
    lastEligibility, consentAgreed: consentAt !== null, mockSignatureCompleted: false, smsVerified: false,
    currentStep: !selection ? 'LOAN_SELECTION' : !document ? 'APPLICATION_UPLOAD' : !lastEligibility ? 'ELIGIBILITY_VALIDATION' : !consentAt ? 'CONSENT' : 'MOCK_SIGNATURE',
    readyToExecute: false,
  })

  return {
    requiresApplicationDocument: true,
    async listLoans() {
      await delay(400)
      return MOCK_LOANS.map(loan => ({ ...loan }))
    },
    async getContext() { return currentContext() },
    async resume() {
      return { workItemId: 1, workflowId: 1, taskCode: 'REPAYMENT_ORDER_CHANGE', status: 'ACTIVE', version: 1, resumeStep: null }
    },
    async saveSelection(loanId, requestedOrder) {
      selection = { loanId, requestedOrder }; consentAt = null; lastEligibility = null
      return currentContext()
    },
    async checkEligibility(loanId): Promise<B01Eligibility> {
      await delay(400)
      const loan = MOCK_LOANS.find(item => item.loanId === loanId)
      if (!loan) throw new ApiError('LOAN_NOT_AVAILABLE', 400, 'The selected loan is not available.')
      const thirdPartyProvider = THIRD_PARTY_LOANS.has(loanId)
      lastEligibility = loan.accelerationDeclared
        ? { ruleCode: 'ELIGIBLE', loanRuleEligible: true, eligible: true, thirdPartyProvider, additionalConfirmationRequired: thirdPartyProvider, ocrMatched: true, mismatchCodes: [], image: { matched: true, status: 'MATCHED', mismatchCodes: [] }, documentFileId: 1, signaturesVerified: false, authenticityVerified: false, manualVerificationRequired: true, automaticProcessingAllowed: false }
        : { ruleCode: 'NOT_ACCELERATED', loanRuleEligible: false, eligible: false, thirdPartyProvider, additionalConfirmationRequired: thirdPartyProvider, ocrMatched: true, mismatchCodes: [], image: { matched: true, status: 'MATCHED', mismatchCodes: [] }, documentFileId: 1, signaturesVerified: false, authenticityVerified: false, manualVerificationRequired: true, automaticProcessingAllowed: false }
      return lastEligibility
    },
    async uploadApplication(file) {
      await delay(700)
      if (!ALLOWED_TYPES.has(file.type)) throw new ApiError('FILE_CONTENT_TYPE_MISMATCH', 400, 'Only PDF, PNG and JPEG are accepted.')
      document = { documentFileId: 1, version: 1, documentTypeCode: 'B01_REPAYMENT_ORDER_APPLICATION', detectedType: 'B01_REPAYMENT_ORDER_APPLICATION', method: 'MOCK', status: 'SUCCEEDED', fields: { loans: [{ maskedAccountNumber: '****T01', balance: 26500000, accelerationDate: '2026-08-31' }], requestedRepaymentOrder: selection?.requestedOrder ?? null }, signatureChecks: { debtorSignatureField: 'NOT_VERIFIED', thirdPartySignatureField: 'NOT_VERIFIED' }, visualChecks: { alignmentStatus: 'ALIGNED', firstBox: selection?.requestedOrder === 'PRINCIPAL_FIRST' ? 'CHECKED' : 'UNCHECKED', secondBox: selection?.requestedOrder === 'INTEREST_FIRST' ? 'CHECKED' : 'UNCHECKED', selectedOrder: selection?.requestedOrder ?? 'NONE', debtorSignatureTrace: 'TRACE_PRESENT', uncertain: false, manualVerificationRequired: true, failureReason: null }, authenticityVerified: false, manualVerificationRequired: true }
      lastEligibility = null; consentAt = null
      return document
    },
    async recordConsent() {
      await delay(400)
      consentAt = new Date().toISOString()
    },
    sign: {
      devHint: sign.devHint,
      async submitSignature(submission) {
        if (!consentAt) throw new ApiError('CONSENT_REQUIRED', 400, 'Explicit consent is required.')
        return sign.submitSignature(submission)
      },
      issueCode: () => sign.issueCode(),
      verifyCode: (challengeId, otp) => sign.verifyCode(challengeId, otp),
      complete: result => sign.complete(result),
    },
    async finalize() {
      await delay(1500)
      // The mock returns DONE to show the design's completed screen.
      return { status: 'DONE', processedAt: new Date().toISOString(), consentAt: consentAt ?? undefined, maskedEmail: 'min***@example.com' }
    },
  }
}
