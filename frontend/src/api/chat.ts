import { ApiError, apiFetch } from './client'

export type TaskCode = 'TRANSACTION_SEARCH' | 'LOAN_INQUIRY' | 'REPAYMENT_ORDER_CHANGE' | 'HOUSING_SAVINGS_CLOSE' | 'DEBT_ADJUSTMENT' | 'INHERITANCE_DEPOSIT'
export type ChatSenderType = 'CUSTOMER' | 'AGENT' | 'SYSTEM'
export type ChatMessageStatus = 'SENT'

export type ChatRoom = {
  id: number
  customerId: number
  createdAt: string
  lastActivityAt: string | null
  oldestRetainedAt: string | null
}

export type ChatMessage = {
  id: number
  chatRoomId: number
  seq: number
  senderType: ChatSenderType
  content: string
  status: ChatMessageStatus
  citations: string | null
  sentAt: string
  requestId: string | null
}

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

/** Contract draft: one row of a chat A-01 result (same fields as Spring TransactionSearchResponse.TransactionView). */
export type ChatTransaction = {
  transactionId: number
  occurredAt: string
  direction: 'IN' | 'OUT'
  amount: number | string
  counterparty: string | null
  memo: string | null
}

/** Contract draft: agentResult.transactionSearch, sent with outcome STARTED when the chat ran A-01. */
export type ChatTransactionSearch = {
  workItemId: number
  accountId: number
  accountNoMasked: string
  accountName: string
  startDate: string
  endDate: string
  periodLabel: string | null
  transactions: ChatTransaction[]
  totalElements: number
  depositTotal: number | string
  withdrawalTotal: number | string
  keyword: string | null
  /** Optional follow-up sentences the customer can send (e.g. widen the period). */
  nextActions: string[] | null
}

/** Contract draft: agentResult.parameterRequest, sent with outcome PARAMETER_REQUIRED. */
export type ChatParameterRequest = {
  taskCode: 'TRANSACTION_SEARCH'
  missing: ('period' | 'account')[]
  reason: 'MISSING' | 'RANGE_TOO_LONG'
  earliestDate: string | null
  accountOptions: { accountId: number; accountNoMasked: string; accountName: string }[] | null
}

/** One task of a composite request, taken from the classifier output (classification.tasks). */
export type CompositeTask = { order: number; taskCode: TaskCode }

export type ChatReply =
  | { kind: 'message'; answer: string }
  | { kind: 'information'; answer: string }
  | { kind: 'clarify'; question: string }
  | { kind: 'unsupported'; reason: string }
  | { kind: 'composite'; answer?: string; tasks?: CompositeTask[] }
  | { kind: 'notStartable'; taskCode?: TaskCode; answer?: string }
  | { kind: 'started'; taskCode: TaskCode; workItemId: number; answer: string }
  | { kind: 'taskAction'; answer: string; action: ChatTaskAction }
  | { kind: 'loans'; loans: LoanSummary[]; answer: string }
  | { kind: 'transactions'; answer: string; result: ChatTransactionSearch }
  | { kind: 'parameterRequest'; answer: string; request: ChatParameterRequest }

export type ChatTaskAction = {
  taskCode: 'REPAYMENT_ORDER_CHANGE'
  label: '신청 화면으로 이동'
  actionRequestId: string
}

export type AgentWorkflow = { workflowId: number; workItemId: number; taskCode: TaskCode; status: string }
export type TaskActionResult = AgentWorkflow
export type AgentExecutionStatus = { workflowId: number; workItemId: number; taskCode: TaskCode; workItemStatus: string; workflowStatus: string }

export type AgentResult = {
  requestId: string
  customerId: number
  outcome: 'STARTED' | 'INFORMATION_ONLY' | 'CLARIFICATION_REQUIRED' | 'UNSUPPORTED' | 'NO_EXECUTABLE_TASK' | 'COMPOSITE_NOT_SUPPORTED' | 'TASK_NOT_AUTOSTART_ENABLED' | 'TASK_ACTION_AVAILABLE' | 'PARAMETER_REQUIRED'
  classification: unknown
  workflow: AgentWorkflow | null
  loanInquiry: { loans: LoanSummary[]; workItemStatus?: string | null; workflowStatus?: string | null } | null
  executionStatus: AgentExecutionStatus | null
  taskAction: ChatTaskAction | null
  /** Contract drafts (not sent by the server yet); read only through readTransactionSearch / readParameterRequest. */
  transactionSearch?: unknown
  parameterRequest?: unknown
  answer: string | null
  answerStatus: 'GENERATED' | 'FAILED' | 'NOT_APPLICABLE'
}

export type AgentRunRequest = { messageRequestId: string; operationRequestId: string; content: string }
export type AgentRun = {
  customerMessage: ChatMessage
  agentMessage: ChatMessage | null
  agentResult: AgentResult
  financialOperationCompleted: boolean
  agentMessageSaved: boolean
}

function requireData<T>(data: T | null): T {
  if (data === null || data === undefined) throw new ApiError('INVALID_RESPONSE', 200, 'Response data is missing.')
  return data
}

export const chatApi = {
  async createRoom(): Promise<ChatRoom> {
    return requireData((await apiFetch<ChatRoom>('/api/v1/customer/chat/rooms', { method: 'POST' })).data)
  },
  async listRooms(): Promise<ChatRoom[]> {
    return requireData((await apiFetch<ChatRoom[]>('/api/v1/customer/chat/rooms')).data)
  },
  async getOrCreateLatestRoom(): Promise<ChatRoom> {
    const rooms = await this.listRooms()
    return rooms[0] ?? this.createRoom()
  },
  async listMessages(roomId: number): Promise<ChatMessage[]> {
    const messages = requireData((await apiFetch<ChatMessage[]>(`/api/v1/customer/chat/rooms/${roomId}/messages`)).data)
    return messages.slice().sort((left, right) => left.seq - right.seq)
  },
  async runAgent(roomId: number, request: AgentRunRequest, opts: { signal?: AbortSignal } = {}): Promise<AgentRun> {
    const response = await apiFetch<AgentRun>(`/api/v1/customer/chat/rooms/${roomId}/agent-runs`, {
      method: 'POST', body: JSON.stringify(request), signal: opts.signal,
    })
    return requireData(response.data)
  },
  async startTaskAction(roomId: number, action: ChatTaskAction): Promise<TaskActionResult> {
    const response = await apiFetch<TaskActionResult>(`/api/v1/customer/chat/rooms/${roomId}/task-actions`, {
      method: 'POST', body: JSON.stringify({
        actionRequestId: action.actionRequestId,
        taskCode: action.taskCode,
      }),
    })
    return requireData(response.data)
  },
}

const TASK_CODES = new Set<string>(['TRANSACTION_SEARCH', 'LOAN_INQUIRY', 'REPAYMENT_ORDER_CHANGE', 'HOUSING_SAVINGS_CLOSE', 'DEBT_ADJUSTMENT', 'INHERITANCE_DEPOSIT'])

/** Reads classification.tasks defensively; anything unexpected yields no list rather than a guessed one. */
function compositeTasks(classification: unknown): CompositeTask[] | undefined {
  if (typeof classification !== 'object' || classification === null) return undefined
  const tasks = (classification as { tasks?: unknown }).tasks
  if (!Array.isArray(tasks)) return undefined
  const parsed = tasks.flatMap(task => {
    const { order, taskCode } = (task ?? {}) as { order?: unknown; taskCode?: unknown }
    return typeof order === 'number' && typeof taskCode === 'string' && TASK_CODES.has(taskCode)
      ? [{ order, taskCode: taskCode as TaskCode }] : []
  })
  return parsed.length > 1 ? parsed.sort((left, right) => left.order - right.order) : undefined
}

/** Task code of a single classified task, used when the server started no workflow (e.g. TASK_NOT_AUTOSTART_ENABLED). */
function singleTaskCode(classification: unknown): TaskCode | undefined {
  if (typeof classification !== 'object' || classification === null) return undefined
  const tasks = (classification as { tasks?: unknown }).tasks
  if (!Array.isArray(tasks) || tasks.length !== 1) return undefined
  const taskCode = (tasks[0] ?? {} as { taskCode?: unknown }).taskCode
  return typeof taskCode === 'string' && TASK_CODES.has(taskCode) ? taskCode as TaskCode : undefined
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null
const isAmount = (value: unknown) => typeof value === 'number' || (typeof value === 'string' && value.trim() !== '')
const isText = (value: unknown): value is string => typeof value === 'string'
const isNullableText = (value: unknown) => value === null || value === undefined || typeof value === 'string'

function readTransaction(value: unknown): ChatTransaction | null {
  if (!isRecord(value)) return null
  const { transactionId, occurredAt, direction, amount, counterparty, memo } = value
  if (typeof transactionId !== 'number' || !isText(occurredAt) || (direction !== 'IN' && direction !== 'OUT') || !isAmount(amount)
      || !isNullableText(counterparty) || !isNullableText(memo)) return null
  return { transactionId, occurredAt, direction, amount: amount as number | string, counterparty: (counterparty as string | null | undefined) ?? null, memo: (memo as string | null | undefined) ?? null }
}

/** The only place that reads agentResult.transactionSearch; anything malformed is ignored rather than guessed. */
function readTransactionSearch(value: unknown): ChatTransactionSearch | null {
  if (!isRecord(value) || !Array.isArray(value.transactions)) return null
  const { workItemId, accountId, accountNoMasked, accountName, startDate, endDate, periodLabel, totalElements, depositTotal, withdrawalTotal, keyword, nextActions } = value
  if (typeof workItemId !== 'number' || typeof accountId !== 'number' || !isText(accountNoMasked) || !isText(accountName)
      || !isText(startDate) || !isText(endDate) || typeof totalElements !== 'number' || !isAmount(depositTotal) || !isAmount(withdrawalTotal)) return null
  const transactions = value.transactions.map(readTransaction)
  if (transactions.some(row => row === null)) return null
  return {
    workItemId, accountId, accountNoMasked, accountName, startDate, endDate,
    periodLabel: isText(periodLabel) ? periodLabel : null,
    transactions: transactions as ChatTransaction[], totalElements,
    depositTotal: depositTotal as number | string, withdrawalTotal: withdrawalTotal as number | string,
    keyword: isText(keyword) ? keyword : null,
    nextActions: Array.isArray(nextActions) && nextActions.every(isText) ? nextActions : null,
  }
}

/** The only place that reads agentResult.parameterRequest. */
function readParameterRequest(value: unknown): ChatParameterRequest | null {
  if (!isRecord(value) || value.taskCode !== 'TRANSACTION_SEARCH' || !Array.isArray(value.missing)) return null
  const missing = value.missing.filter((item): item is 'period' | 'account' => item === 'period' || item === 'account')
  const reason = value.reason === 'RANGE_TOO_LONG' ? 'RANGE_TOO_LONG' : value.reason === 'MISSING' ? 'MISSING' : null
  if (!reason) return null
  const accountOptions = Array.isArray(value.accountOptions)
    ? value.accountOptions.flatMap(option => isRecord(option) && typeof option.accountId === 'number' && isText(option.accountNoMasked) && isText(option.accountName)
      ? [{ accountId: option.accountId, accountNoMasked: option.accountNoMasked, accountName: option.accountName }] : [])
    : null
  return { taskCode: 'TRANSACTION_SEARCH', missing, reason, earliestDate: isText(value.earliestDate) ? value.earliestDate : null, accountOptions }
}

export function replyFromAgentRun(run: AgentRun): ChatReply {
  const answer = run.agentMessage?.content || run.agentResult.answer || '요청 결과를 확인하지 못했어요.'
  const loans = run.agentResult.loanInquiry?.loans
  if (loans) return { kind: 'loans', loans, answer }
  const transactionSearch = readTransactionSearch(run.agentResult.transactionSearch)
  if (transactionSearch) return { kind: 'transactions', answer, result: transactionSearch }
  const parameterRequest = run.agentResult.outcome === 'PARAMETER_REQUIRED' ? readParameterRequest(run.agentResult.parameterRequest) : null
  if (parameterRequest) return { kind: 'parameterRequest', answer, request: parameterRequest }
  if (run.agentResult.taskAction) return { kind: 'taskAction', answer, action: run.agentResult.taskAction }
  if (run.agentResult.outcome === 'STARTED' && run.agentResult.workflow
      && run.agentResult.workflow.taskCode !== 'LOAN_INQUIRY') {
    return { kind: 'started', taskCode: run.agentResult.workflow.taskCode,
      workItemId: run.agentResult.workflow.workItemId, answer }
  }
  switch (run.agentResult.outcome) {
    case 'CLARIFICATION_REQUIRED': return { kind: 'clarify', question: answer }
    case 'UNSUPPORTED': return { kind: 'unsupported', reason: answer }
    case 'COMPOSITE_NOT_SUPPORTED': return { kind: 'composite', answer, tasks: compositeTasks(run.agentResult.classification) }
    case 'INFORMATION_ONLY': return { kind: 'information', answer }
    case 'TASK_NOT_AUTOSTART_ENABLED': return { kind: 'notStartable', taskCode: run.agentResult.workflow?.taskCode ?? singleTaskCode(run.agentResult.classification), answer }
    default: return { kind: 'message', answer }
  }
}

export function replyFromChatMessage(message: ChatMessage): ChatReply {
  if (message.citations) {
    try {
      const parsed = JSON.parse(message.citations) as { taskAction?: ChatTaskAction }
      const action = parsed.taskAction
      if (action?.taskCode === 'REPAYMENT_ORDER_CHANGE'
          && action.label === '신청 화면으로 이동'
          && typeof action.actionRequestId === 'string') {
        return { kind: 'taskAction', answer: message.content, action }
      }
    } catch {
      // Ordinary citations and legacy messages remain plain chat messages.
    }
  }
  return { kind: 'message', answer: message.content }
}
