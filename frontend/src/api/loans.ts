import { ApiError, apiFetch } from './client'

/**
 * Contract draft for GET /api/v1/customer/loans (customer from the JWT, read only, no notification).
 * Field names follow the A-02 LoanSummary; accelerationDate, repaymentOrderSteps, repaymentOrderChangedAt and asOf are new.
 */
export type RepaymentStep = 'COST' | 'INTEREST' | 'PRINCIPAL'

export type CustomerLoan = {
  loanId: number
  loanNoMasked: string
  loanName: string | null
  /** Won. The server sends a JSON number; a string is accepted the same way as the chat A-02 response. */
  balance: number | string
  /** Annual percent; null when the loan row has no rate. */
  interestRate: number | string | null
  overdueDays: number
  accelerationDeclared: boolean
  /** YYYY-MM-DD, only when the benefit of term was lost. */
  accelerationDate: string | null
  repaymentOrder: 'INTEREST_FIRST' | 'PRINCIPAL_FIRST'
  repaymentOrderSteps: RepaymentStep[]
  /** ISO instant of the last B-01 change; null when no change was recorded. */
  repaymentOrderChangedAt: string | null
}

/** asOf is the ISO instant the server read the loans. */
export type CustomerLoanList = { asOf: string; loans: CustomerLoan[] }

export interface LoanAccountsAdapter {
  list(): Promise<CustomerLoanList>
}

export function createRealLoanAccountsAdapter(): LoanAccountsAdapter {
  return {
    async list() {
      const requestId = `loans-${crypto.randomUUID()}`
      const envelope = await apiFetch<CustomerLoanList>(`/api/v1/customer/loans?requestId=${encodeURIComponent(requestId)}`)
      if (!envelope.data) throw new ApiError('INVALID_RESPONSE', 200, 'The response has no data.')
      return envelope.data
    },
  }
}
