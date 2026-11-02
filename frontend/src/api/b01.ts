import type { SignAdapter } from './sign'

// Must equal the server constant B01VerificationService.NOTICE_VERSION.
export const B01_NOTICE_VERSION = 'B01_NOTICE_V1'

export type RepaymentOrder = 'INTEREST_FIRST' | 'PRINCIPAL_FIRST'

/** accelerationDate is present only when the server provides it. */
export type B01Loan = {
  loanId: string
  loanName: string
  loanNoMasked: string
  balance: number | string
  overdueDays: number
  accelerationDeclared: boolean
  accelerationDate?: string
  repaymentOrder: RepaymentOrder
}

export type B01RuleCode = 'ELIGIBLE' | 'NOT_ACCELERATED' | 'OUTSOURCED_LOAN' | 'AGREEMENT_LOAN' | 'ALREADY_APPLIED'

export type B01Eligibility = { ruleCode: B01RuleCode; eligible: boolean; thirdPartyProvider: boolean }

/**
 * Times are ISO strings; values the server does not provide are omitted.
 * Until an execution API exists, the real adapter returns PENDING.
 */
export type B01Result = { status: 'DONE' | 'PENDING'; processedAt?: string; consentAt?: string; maskedEmail?: string }

/** One adapter per B-01 backend; every method rejects with ApiError. */
export interface B01Adapter {
  requiresApplicationDocument: boolean
  // No matching API yet; the loan list source is undecided.
  listLoans(): Promise<B01Loan[]>
  // POST /api/v1/customer/b01/work-items/{workItemId}/eligibility
  checkEligibility(loanId: string, requestedOrder: RepaymentOrder): Promise<B01Eligibility>
  // POST /api/v1/customer/b01/work-items/{workItemId}/documents/application (multipart)
  uploadApplication(file: File): Promise<void>
  // POST /api/v1/customer/b01/work-items/{workItemId}/consent (with documentFileId and B01_NOTICE_VERSION)
  recordConsent(input: { loanId: string; requestedOrder: RepaymentOrder }): Promise<void>
  // mock-signature, sms/requests, sms/verifications
  sign: SignAdapter
  // No execution API yet.
  finalize(): Promise<B01Result>
}
