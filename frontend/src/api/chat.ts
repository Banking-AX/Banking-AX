import { chatMock } from './mock/chatMock'

export type TaskCode = 'TRANSACTION_SEARCH' | 'LOAN_INQUIRY' | 'REPAYMENT_ORDER_CHANGE' | 'HOUSING_SAVINGS_CLOSE' | 'DEBT_ADJUSTMENT' | 'INHERITANCE_DEPOSIT'

/** Server limits: message 2~2000 chars, recentMessages up to 5, requestId 1~90 chars. */
export type ChatSendRequest = { requestId: string; message: string; recentMessages: string[] }

export type LoanSummary = {
  loanId: number
  loanNoMasked: string
  loanName: string
  // execute passes Spring's JSON numbers through; messages/start serializes Pydantic Decimal as strings.
  balance: number | string
  interestRate: number | string
  overdueDays: number
  accelerationDeclared: boolean
  repaymentOrder: string
}

export type ChatReply =
  | { kind: 'clarify'; question: string }
  | { kind: 'unsupported'; reason: string }
  | { kind: 'composite' }
  | { kind: 'notStartable'; taskCode: TaskCode }
  | { kind: 'loans'; loans: LoanSummary[] }

export interface ChatApi {
  /** Rejects with ApiError on failure, or with an AbortError when the signal aborts. */
  send(req: ChatSendRequest, opts?: { signal?: AbortSignal }): Promise<ChatReply>
}

// Mock only; step 5 connects POST /api/v1/customer-agent/messages/start.
export const chatApi: ChatApi = chatMock
