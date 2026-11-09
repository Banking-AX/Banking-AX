import { ApiError, apiFetch, apiFetchBlob } from './client'

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
  | { kind: 'taskAction'; answer: string; action: ChatTaskAction; documentAction?: B01DocumentAction }
  | { kind: 'b01LoanSelection'; answer: string; action: B01LoanSelectionAction }
  | { kind: 'loanDetailsAction'; answer: string }
  | { kind: 'loans'; loans: LoanSummary[]; answer: string }
  | { kind: 'transactions'; answer: string; result: ChatTransactionSearch }
  | { kind: 'parameterRequest'; answer: string; request: ChatParameterRequest }
  | { kind: 'b01Reconnect'; answer: string; action: B01ReconnectAction }

export type ChatTaskAction = {
  taskCode: 'REPAYMENT_ORDER_CHANGE'
  label: '신청 화면으로 이동'
  actionRequestId: string
}

export type B01LoanSelectionAction = {
  type: 'SELECT_B01_LOAN'
  candidates: { number: number; loanId: number; loanName: string; loanNoMasked: string }[]
}

export type B01DocumentAction = {
  type: 'DOWNLOAD_B01_APPLICATION'
  fileName: 'b01-repayment-order-change-application-201903.pdf'
  downloadPath: '/api/v1/customer/b01/application-form'
}

/** One unfinished B-01 work item in another chat room. Status and ownership are re-checked by Spring on every choice. */
export type B01ReconnectCandidate = {
  workItemId: number
  status: 'ACTIVE' | 'ON_HOLD'
  originalChatRoomId: number | null
  originalChatRoomAvailable: boolean
}

/** agentResult.b01ReconnectAction, also stored in chat_message.citations. allowStartNew is a display hint only. */
export type B01ReconnectAction = {
  type: 'SELECT_B01_RECONNECT'
  allowStartNew: boolean
  candidates: B01ReconnectCandidate[]
}

export type B01ReconnectChoice = 'CONTINUE_EXISTING' | 'START_NEW'
export type B01ReconnectRequest = { requestId: string; choice: B01ReconnectChoice; workItemId: number | null }

/** Spring B01ReconnectService.ReconnectResult. */
export type B01ReconnectResult = {
  outcome: 'EXISTING_SELECTED' | 'NEW_CREATED' | 'SAME_CHAT_REUSED' | 'ACTIVE_WORK_ITEM_CONFLICT' | 'REQUEST_REPLAYED'
  workItemId: number
  workItemStatus: string
  originalChatRoomId: number | null
  originalChatRoomAvailable: boolean
  navigationTarget: 'ORIGINAL_CHAT' | 'B01_TASK' | 'START_B01_GRAPH'
}

export type AgentWorkflow = { workflowId: number; workItemId: number; taskCode: TaskCode; status: string }
export type TaskActionResult = AgentWorkflow
export type AgentExecutionStatus = { workflowId: number; workItemId: number; taskCode: TaskCode; workItemStatus: string; workflowStatus: string }

export type AgentResult = {
  requestId: string
  customerId: number
  outcome: 'STARTED' | 'INFORMATION_ONLY' | 'CLARIFICATION_REQUIRED' | 'UNSUPPORTED' | 'NO_EXECUTABLE_TASK' | 'COMPOSITE_NOT_SUPPORTED' | 'TASK_NOT_AUTOSTART_ENABLED' | 'TASK_ACTION_AVAILABLE' | 'PARAMETER_REQUIRED'
    | 'B01_RECONNECT_CONFIRMATION_REQUIRED' | 'B01_ACTIVE_WORK_ITEM_CONFLICT'
  classification: unknown
  workflow: AgentWorkflow | null
  loanInquiry: { loans: LoanSummary[]; workItemStatus?: string | null; workflowStatus?: string | null } | null
  executionStatus: AgentExecutionStatus | null
  taskAction: ChatTaskAction | null
  b01LoanSelectionAction?: unknown
  b01DocumentAction?: unknown
  /** Read only through readB01ReconnectAction. */
  b01ReconnectAction?: unknown
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
  /** The customer is taken from the JWT; Spring re-checks ownership and the latest work item status. */
  async reconnectB01(roomId: number, request: B01ReconnectRequest): Promise<B01ReconnectResult> {
    const response = await apiFetch<unknown>(`/api/v1/customer/chat/rooms/${roomId}/b01/reconnect`, {
      method: 'POST', body: JSON.stringify(request),
    })
    const result = readB01ReconnectResult(requireData(response.data))
    if (!result) throw new ApiError('INVALID_RESPONSE', 200, 'B-01 reconnect response is invalid.')
    return result
  },
  async downloadB01ApplicationForm(): Promise<Blob> {
    return apiFetchBlob('/api/v1/customer/b01/application-form')
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

const isPositiveId = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value) && value > 0

function readB01ReconnectCandidate(value: unknown): B01ReconnectCandidate | null {
  if (!isRecord(value)) return null
  const { workItemId, status, originalChatRoomId, originalChatRoomAvailable } = value
  if (!isPositiveId(workItemId) || (status !== 'ACTIVE' && status !== 'ON_HOLD')
      || (originalChatRoomId !== null && originalChatRoomId !== undefined && !isPositiveId(originalChatRoomId))
      || typeof originalChatRoomAvailable !== 'boolean') return null
  const roomId = isPositiveId(originalChatRoomId) ? originalChatRoomId : null
  if (originalChatRoomAvailable && roomId === null) return null
  return { workItemId, status, originalChatRoomId: roomId, originalChatRoomAvailable }
}

/** The only place that reads b01ReconnectAction (agent result or citations); anything malformed shows no buttons. */
function readB01ReconnectAction(value: unknown): B01ReconnectAction | null {
  if (!isRecord(value) || value.type !== 'SELECT_B01_RECONNECT' || typeof value.allowStartNew !== 'boolean'
      || !Array.isArray(value.candidates) || value.candidates.length === 0) return null
  const candidates = value.candidates.map(readB01ReconnectCandidate)
  if (candidates.some(candidate => candidate === null)) return null
  const ids = new Set(candidates.map(candidate => candidate!.workItemId))
  if (ids.size !== candidates.length) return null
  return { type: 'SELECT_B01_RECONNECT', allowStartNew: value.allowStartNew, candidates: candidates as B01ReconnectCandidate[] }
}

function readB01LoanSelectionAction(value: unknown): B01LoanSelectionAction | null {
  if (!isRecord(value) || value.type !== 'SELECT_B01_LOAN'
      || !Array.isArray(value.candidates) || value.candidates.length === 0) return null
  const candidates = value.candidates.flatMap(candidate => {
    if (!isRecord(candidate)) return []
    const { number, loanId, loanName, loanNoMasked } = candidate
    if (!isPositiveId(number) || !isPositiveId(loanId) || !isText(loanName)
        || !isText(loanNoMasked) || !loanNoMasked.includes('*')) return []
    return [{ number, loanId, loanName, loanNoMasked }]
  })
  if (candidates.length !== value.candidates.length) return null
  if (new Set(candidates.map(candidate => candidate.number)).size !== candidates.length
      || new Set(candidates.map(candidate => candidate.loanId)).size !== candidates.length) return null
  return { type: 'SELECT_B01_LOAN', candidates }
}

function readB01DocumentAction(value: unknown): B01DocumentAction | null {
  if (!isRecord(value)
      || value.type !== 'DOWNLOAD_B01_APPLICATION'
      || value.fileName !== 'b01-repayment-order-change-application-201903.pdf'
      || value.downloadPath !== '/api/v1/customer/b01/application-form') return null
  return {
    type: 'DOWNLOAD_B01_APPLICATION',
    fileName: 'b01-repayment-order-change-application-201903.pdf',
    downloadPath: '/api/v1/customer/b01/application-form',
  }
}

const RECONNECT_OUTCOMES = new Set(['EXISTING_SELECTED', 'NEW_CREATED', 'SAME_CHAT_REUSED', 'ACTIVE_WORK_ITEM_CONFLICT', 'REQUEST_REPLAYED'])
const RECONNECT_TARGETS = new Set(['ORIGINAL_CHAT', 'B01_TASK', 'START_B01_GRAPH'])

function readB01ReconnectResult(value: unknown): B01ReconnectResult | null {
  if (!isRecord(value)) return null
  const { outcome, workItemId, workItemStatus, originalChatRoomId, originalChatRoomAvailable, navigationTarget } = value
  if (!isText(outcome) || !RECONNECT_OUTCOMES.has(outcome) || !isPositiveId(workItemId) || !isText(workItemStatus)
      || (originalChatRoomId !== null && originalChatRoomId !== undefined && !isPositiveId(originalChatRoomId))
      || typeof originalChatRoomAvailable !== 'boolean' || !isText(navigationTarget) || !RECONNECT_TARGETS.has(navigationTarget)) return null
  const roomId = isPositiveId(originalChatRoomId) ? originalChatRoomId : null
  // Spring pairs EXISTING_SELECTED/ACTIVE_WORK_ITEM_CONFLICT with a page target and the others with START_B01_GRAPH.
  const startsGraph = outcome === 'NEW_CREATED' || outcome === 'SAME_CHAT_REUSED' || outcome === 'REQUEST_REPLAYED'
  if (startsGraph !== (navigationTarget === 'START_B01_GRAPH')) return null
  if (navigationTarget === 'ORIGINAL_CHAT' && (roomId === null || !originalChatRoomAvailable)) return null
  return {
    outcome: outcome as B01ReconnectResult['outcome'], workItemId, workItemStatus,
    originalChatRoomId: roomId, originalChatRoomAvailable,
    navigationTarget: navigationTarget as B01ReconnectResult['navigationTarget'],
  }
}

/** What the chat does after a reconnect choice. Pages are built from IDs and the app's own routes, never from server URLs. */
export type B01ReconnectNextStep =
  | { type: 'openRoom'; roomId: number; workItemId: number }
  | { type: 'openTask'; workItemId: number }
  | { type: 'startGraph'; workItemId: number }
  | { type: 'conflict'; workItemId: number }

export function b01ReconnectNextStep(result: B01ReconnectResult): B01ReconnectNextStep {
  const { workItemId } = result
  if (result.outcome === 'ACTIVE_WORK_ITEM_CONFLICT') return { type: 'conflict', workItemId }
  if (result.navigationTarget === 'START_B01_GRAPH') return { type: 'startGraph', workItemId }
  if (result.navigationTarget === 'ORIGINAL_CHAT' && result.originalChatRoomId !== null) {
    return { type: 'openRoom', roomId: result.originalChatRoomId, workItemId }
  }
  return { type: 'openTask', workItemId }
}

export function replyFromAgentRun(run: AgentRun): ChatReply {
  const answer = run.agentMessage?.content || run.agentResult.answer || '요청 결과를 확인하지 못했어요.'
  const loans = run.agentResult.loanInquiry?.loans
  if (loans) return { kind: 'loans', loans, answer }
  if (run.agentMessage) {
    const persisted = replyFromChatMessage(run.agentMessage)
    if (persisted.kind === 'loanDetailsAction') return persisted
  }
  const transactionSearch = readTransactionSearch(run.agentResult.transactionSearch)
  if (transactionSearch) return { kind: 'transactions', answer, result: transactionSearch }
  const parameterRequest = run.agentResult.outcome === 'PARAMETER_REQUIRED' ? readParameterRequest(run.agentResult.parameterRequest) : null
  if (parameterRequest) return { kind: 'parameterRequest', answer, request: parameterRequest }
  const reconnect = readB01ReconnectAction(run.agentResult.b01ReconnectAction)
  if (reconnect) return { kind: 'b01Reconnect', answer, action: reconnect }
  const loanSelection = readB01LoanSelectionAction(run.agentResult.b01LoanSelectionAction)
  if (loanSelection) return { kind: 'b01LoanSelection', answer, action: loanSelection }
  if (run.agentResult.taskAction) {
    const documentAction = readB01DocumentAction(run.agentResult.b01DocumentAction)
    return { kind: 'taskAction', answer, action: run.agentResult.taskAction,
      ...(documentAction ? { documentAction } : {}) }
  }
  // B-01 opens its application page only through taskAction (after ELIGIBLE); a STARTED graph answer stays in the chat.
  if (run.agentResult.outcome === 'STARTED' && run.agentResult.workflow
      && run.agentResult.workflow.taskCode !== 'LOAN_INQUIRY'
      && run.agentResult.workflow.taskCode !== 'REPAYMENT_ORDER_CHANGE') {
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
      const parsed: unknown = JSON.parse(message.citations)
      if (isRecord(parsed)) {
        const action = parsed.taskAction
        if (isRecord(action)
            && action.taskCode === 'REPAYMENT_ORDER_CHANGE'
            && action.label === '신청 화면으로 이동'
            && typeof action.actionRequestId === 'string') {
          const documentAction = readB01DocumentAction(parsed.b01DocumentAction)
          return { kind: 'taskAction', answer: message.content, action: {
            taskCode: action.taskCode, label: action.label, actionRequestId: action.actionRequestId,
          }, ...(documentAction ? { documentAction } : {}) }
        }
        const loanSelection = readB01LoanSelectionAction(parsed.b01LoanSelectionAction)
        if (loanSelection) return { kind: 'b01LoanSelection', answer: message.content, action: loanSelection }
        const uiAction = parsed.uiAction
        if (isRecord(uiAction)
            && uiAction.type === 'OPEN_CUSTOMER_LOANS'
            && uiAction.taskCode === 'LOAN_INQUIRY') {
          return { kind: 'loanDetailsAction', answer: message.content }
        }
        const reconnect = readB01ReconnectAction(parsed.b01ReconnectAction)
        if (reconnect) return { kind: 'b01Reconnect', answer: message.content, action: reconnect }
      }
    } catch {
      // Ordinary citations and legacy messages remain plain chat messages.
    }
  }
  return { kind: 'message', answer: message.content }
}
