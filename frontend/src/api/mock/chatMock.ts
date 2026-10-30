import { ApiError } from '../client'
import type { ChatApi, ChatReply, LoanSummary, TaskCode } from '../chat'

// Mock only: keyword rules stand in for the intent classifier until messages/start is connected.
const DELAY_MS = 800

const NOT_STARTABLE: [string, TaskCode][] = [
  ['거래내역', 'TRANSACTION_SEARCH'],
  ['충당순서', 'REPAYMENT_ORDER_CHANGE'],
  ['청약', 'HOUSING_SAVINGS_CLOSE'],
  ['채무조정', 'DEBT_ADJUSTMENT'],
  ['상속', 'INHERITANCE_DEPOSIT'],
]

// Mock data only: balance is a string in the first loan and a number in the second to cover both server formats.
const MOCK_LOANS: LoanSummary[] = [
  { loanId: 1, loanName: 'AX 개인 신용대출', loanNoMasked: '210-***-557731', balance: '17200000.0', interestRate: '6.2', overdueDays: 45, accelerationDeclared: true, repaymentOrder: 'PRINCIPAL_FIRST' },
  { loanId: 2, loanName: 'AX 전세자금대출', loanNoMasked: '230-***-114820', balance: 120000000, interestRate: '4.1', overdueDays: 0, accelerationDeclared: false, repaymentOrder: 'INTEREST_FIRST' },
]

function delay(signal?: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')) }
    const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve() }, DELAY_MS)
    if (signal?.aborted) abort()
    else signal?.addEventListener('abort', abort, { once: true })
  })
}

function reply(message: string): ChatReply {
  if (message.includes('오류')) throw new ApiError('LOAN_INQUIRY_UNAVAILABLE', 503, 'Mock failure.')
  if (message.includes('주택담보')) return { kind: 'unsupported', reason: '주택담보대출 심사는 AI 금융 도우미에서 도와드릴 수 없는 업무예요.' }
  if (message.includes('대출 없음')) return { kind: 'loans', loans: [] }
  if (message.includes('대출')) return { kind: 'loans', loans: MOCK_LOANS }
  if (message.includes('동시에')) return { kind: 'composite' }
  const match = NOT_STARTABLE.find(([keyword]) => message.includes(keyword))
  if (match) return { kind: 'notStartable', taskCode: match[1] }
  return { kind: 'clarify', question: '어떤 업무를 도와드릴까요? 업무 이름을 알려 주세요.' }
}

export const chatMock: ChatApi = {
  async send(req, opts = {}) {
    await delay(opts.signal)
    return reply(req.message)
  },
}
