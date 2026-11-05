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

export type ChatReply =
  | { kind: 'message'; answer: string }
  | { kind: 'clarify'; question: string }
  | { kind: 'unsupported'; reason: string }
  | { kind: 'composite'; answer?: string }
  | { kind: 'notStartable'; taskCode?: TaskCode; answer?: string }
  | { kind: 'taskAction'; answer: string; action: ChatTaskAction }
  | { kind: 'loans'; loans: LoanSummary[]; answer: string }

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
  outcome: 'STARTED' | 'INFORMATION_ONLY' | 'CLARIFICATION_REQUIRED' | 'UNSUPPORTED' | 'NO_EXECUTABLE_TASK' | 'COMPOSITE_NOT_SUPPORTED' | 'TASK_NOT_AUTOSTART_ENABLED' | 'TASK_ACTION_AVAILABLE'
  classification: unknown
  workflow: AgentWorkflow | null
  loanInquiry: { loans: LoanSummary[]; workItemStatus?: string | null; workflowStatus?: string | null } | null
  executionStatus: AgentExecutionStatus | null
  taskAction: ChatTaskAction | null
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

export function replyFromAgentRun(run: AgentRun): ChatReply {
  const answer = run.agentMessage?.content || run.agentResult.answer || '요청 결과를 확인하지 못했어요.'
  const loans = run.agentResult.loanInquiry?.loans
  if (loans) return { kind: 'loans', loans, answer }
  if (run.agentResult.taskAction) return { kind: 'taskAction', answer, action: run.agentResult.taskAction }
  switch (run.agentResult.outcome) {
    case 'CLARIFICATION_REQUIRED': return { kind: 'clarify', question: answer }
    case 'UNSUPPORTED': return { kind: 'unsupported', reason: answer }
    case 'COMPOSITE_NOT_SUPPORTED': return { kind: 'composite', answer }
    case 'TASK_NOT_AUTOSTART_ENABLED': return { kind: 'notStartable', taskCode: run.agentResult.workflow?.taskCode, answer }
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
