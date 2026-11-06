import { ApiError } from '../client'
import type { CustomerLoanList, LoanAccountsAdapter } from '../loans'

// Mock 샘플: DEV preview only (/loans?mock=1). Not a server response; never used outside import.meta.env.DEV.
const DELAY_MS = 400

const SAMPLE: CustomerLoanList = {
  asOf: '2026-10-20T01:00:00Z',
  loans: [
    { loanId: 101, loanNoMasked: '210-***-557731', loanName: 'AX 개인 신용대출', balance: 17200000, interestRate: 7.2, overdueDays: 45,
      accelerationDeclared: true, accelerationDate: '2026-09-30', repaymentOrder: 'PRINCIPAL_FIRST',
      repaymentOrderSteps: ['COST', 'PRINCIPAL', 'INTEREST'], repaymentOrderChangedAt: '2026-10-20T06:11:00Z' },
    { loanId: 102, loanNoMasked: '230-***-114820', loanName: 'AX 전세자금대출', balance: 120000000, interestRate: 3.85, overdueDays: 0,
      accelerationDeclared: false, accelerationDate: null, repaymentOrder: 'INTEREST_FIRST',
      repaymentOrderSteps: ['COST', 'INTEREST', 'PRINCIPAL'], repaymentOrderChangedAt: null },
  ],
}

export type LoanMockScenario = 'sample' | 'empty' | 'error'

export function createMockLoanAccountsAdapter(scenario: LoanMockScenario = 'sample'): LoanAccountsAdapter {
  return {
    async list() {
      await new Promise(resolve => setTimeout(resolve, DELAY_MS))
      if (scenario === 'error') throw new ApiError('LOAN_INQUIRY_UNAVAILABLE', 503, 'Mock failure.')
      return scenario === 'empty' ? { ...SAMPLE, loans: [] } : SAMPLE
    },
  }
}
