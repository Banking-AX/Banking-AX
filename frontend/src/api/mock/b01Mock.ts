import { ApiError } from '../client'
import type { B01Adapter, B01Eligibility, B01Loan } from '../b01'
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

  return {
    requiresApplicationDocument: true,
    async listLoans() {
      await delay(400)
      return MOCK_LOANS.map(loan => ({ ...loan }))
    },
    async checkEligibility(loanId): Promise<B01Eligibility> {
      await delay(400)
      const loan = MOCK_LOANS.find(item => item.loanId === loanId)
      if (!loan) throw new ApiError('LOAN_NOT_AVAILABLE', 400, 'The selected loan is not available.')
      const thirdPartyProvider = THIRD_PARTY_LOANS.has(loanId)
      return loan.accelerationDeclared
        ? { ruleCode: 'ELIGIBLE', eligible: true, thirdPartyProvider }
        : { ruleCode: 'NOT_ACCELERATED', eligible: false, thirdPartyProvider }
    },
    async uploadApplication(file) {
      await delay(700)
      if (!ALLOWED_TYPES.has(file.type)) throw new ApiError('FILE_CONTENT_TYPE_MISMATCH', 400, 'Only PDF, PNG and JPEG are accepted.')
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
