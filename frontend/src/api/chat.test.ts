import { afterEach, describe, expect, it, vi } from 'vitest'
import { chatApi, replyFromAgentRun, replyFromChatMessage, type AgentRun, type ChatMessage, type ChatRoom } from './chat'
import { setAccessToken } from './client'

const room: ChatRoom = {
  id: 7, customerId: 1, createdAt: '2026-01-01T00:00:00Z',
  lastActivityAt: '2026-01-02T00:00:00Z', oldestRetainedAt: '2026-01-01T00:00:00Z',
}

const message = (id: number, seq: number, senderType: 'CUSTOMER' | 'AGENT', content: string): ChatMessage => ({
  id, chatRoomId: 7, seq, senderType, content, status: 'SENT', citations: null,
  sentAt: `2026-01-01T00:00:0${seq}Z`, requestId: senderType === 'CUSTOMER' ? 'msg-1' : 'agent-1',
})

const envelope = (data: unknown) => JSON.stringify({
  requestId: 'test', taskCode: 'CHAT_AGENT', workItemId: null,
  caseId: null, revision: null, data, error: null,
})

afterEach(() => {
  vi.unstubAllGlobals()
  setAccessToken(null)
})

describe('Spring chat BFF client', () => {
  it('reuses the most recent room without creating another room', async () => {
    setAccessToken('customer-token')
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(envelope([room]), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(chatApi.getOrCreateLatestRoom()).resolves.toEqual(room)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/customer/chat/rooms')
  })

  it('creates one room when the customer has no room', async () => {
    setAccessToken('customer-token')
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') return new Response(envelope(room), { status: 201 })
      return new Response(envelope([]), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(chatApi.getOrCreateLatestRoom()).resolves.toEqual(room)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.filter(call => call[1]?.method === 'POST')).toHaveLength(1)
  })

  it('sends one agent-runs request with both idempotency IDs and no customerId', async () => {
    setAccessToken('customer-token')
    const run: AgentRun = {
      customerMessage: message(10, 1, 'CUSTOMER', '내 대출정보 조회해 줘'),
      agentMessage: message(11, 2, 'AGENT', '대출계좌 3건을 조회했어요.'),
      agentResult: {
        requestId: 'op-1', customerId: 1, outcome: 'STARTED', classification: {},
        workflow: { workflowId: 20, workItemId: 21, taskCode: 'LOAN_INQUIRY', status: 'COMPLETED' },
        loanInquiry: { loans: [{ loanId: 1, loanNoMasked: '210-***-000001', loanName: '대출', balance: '1000', interestRate: '4.1', overdueDays: 0, accelerationDeclared: false, repaymentOrder: 'INTEREST_FIRST' }] },
        executionStatus: { workflowId: 20, workItemId: 21, taskCode: 'LOAN_INQUIRY', workItemStatus: 'DONE', workflowStatus: 'COMPLETED' },
        taskAction: null,
        answer: '대출계좌 3건을 조회했어요.', answerStatus: 'GENERATED',
      },
      financialOperationCompleted: true, agentMessageSaved: true,
    }
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(envelope(run), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const request = { messageRequestId: 'msg-1', operationRequestId: 'op-1', content: '내 대출정보 조회해 줘' }
    const result = await chatApi.runAgent(7, request)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/customer/chat/rooms/7/agent-runs')
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual(request)
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).not.toHaveProperty('customerId')
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Authorization')).toBe('Bearer customer-token')
    expect(replyFromAgentRun(result)).toMatchObject({ kind: 'loans', answer: run.agentMessage?.content })
  })

  it('starts a persisted B01 action with no client-provided customer id', async () => {
    setAccessToken('customer-token')
    const action = { taskCode: 'REPAYMENT_ORDER_CHANGE', label: '신청 화면으로 이동', actionRequestId: 'op-b01' } as const
    const result = { workflowId: 31, workItemId: 41, taskCode: 'REPAYMENT_ORDER_CHANGE', status: 'ACTIVE' }
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
      new Response(envelope(result), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(chatApi.startTaskAction(7, action)).resolves.toEqual(result)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/customer/chat/rooms/7/task-actions')
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      actionRequestId: 'op-b01', taskCode: 'REPAYMENT_ORDER_CHANGE',
    })
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).not.toHaveProperty('customerId')
  })

  it('restores a structured B01 action from the persisted agent message', () => {
    const agent = message(12, 2, 'AGENT', '채무변제충당순서 변경 신청을 도와드리겠습니다.')
    agent.citations = JSON.stringify({ taskAction: {
      taskCode: 'REPAYMENT_ORDER_CHANGE', label: '신청 화면으로 이동', actionRequestId: 'stable-action',
    } })

    expect(replyFromChatMessage(agent)).toEqual({
      kind: 'taskAction', answer: agent.content,
      action: { taskCode: 'REPAYMENT_ORDER_CHANGE', label: '신청 화면으로 이동', actionRequestId: 'stable-action' },
    })
  })

  it('restores only the A02 loan-page action from persisted UI metadata', () => {
    const agent = message(13, 2, 'AGENT', '대출계좌 3건을 조회했습니다.')
    agent.citations = JSON.stringify({ uiAction: {
      type: 'OPEN_CUSTOMER_LOANS', taskCode: 'LOAN_INQUIRY',
    } })

    expect(replyFromChatMessage(agent)).toEqual({
      kind: 'loanDetailsAction', answer: agent.content,
    })
  })

  it('ignores malformed or server-directed loan-page metadata', () => {
    const malformed = message(14, 2, 'AGENT', '조회 결과')
    malformed.citations = JSON.stringify({ uiAction: {
      type: 'OPEN_CUSTOMER_LOANS', taskCode: 'OTHER', url: 'https://example.invalid',
    } })

    expect(replyFromChatMessage(malformed)).toEqual({ kind: 'message', answer: malformed.content })
  })

  it('does not create a loan-page action for an empty A02 result', () => {
    const run: AgentRun = {
      customerMessage: message(20, 1, 'CUSTOMER', '내 대출정보 조회해 줘'),
      agentMessage: message(21, 2, 'AGENT', '조회된 대출계좌가 없습니다.'),
      agentResult: {
        requestId: 'op-empty', customerId: 1, outcome: 'STARTED', classification: {},
        workflow: { workflowId: 30, workItemId: 31, taskCode: 'LOAN_INQUIRY', status: 'COMPLETED' },
        loanInquiry: { loans: [] },
        executionStatus: { workflowId: 30, workItemId: 31, taskCode: 'LOAN_INQUIRY', workItemStatus: 'DONE', workflowStatus: 'COMPLETED' },
        taskAction: null, answer: '조회된 대출계좌가 없습니다.', answerStatus: 'GENERATED',
      },
      financialOperationCompleted: true, agentMessageSaved: true,
    }

    expect(replyFromAgentRun(run)).toEqual({
      kind: 'loans', loans: [], answer: '조회된 대출계좌가 없습니다.',
    })
  })

  it('restores messages in server sequence order', async () => {
    setAccessToken('customer-token')
    vi.stubGlobal('fetch', vi.fn(async () => new Response(envelope([
      message(11, 2, 'AGENT', '답변'), message(10, 1, 'CUSTOMER', '질문'),
    ]), { status: 200 })))

    await expect(chatApi.listMessages(7)).resolves.toEqual([
      message(10, 1, 'CUSTOMER', '질문'), message(11, 2, 'AGENT', '답변'),
    ])
  })

  it('returns screen navigation for a started non-financial task', () => {
    const run: AgentRun = {
      customerMessage: message(10, 1, 'CUSTOMER', '거래내역 조회해 줘'),
      agentMessage: message(11, 2, 'AGENT', '업무를 준비했습니다.'),
      agentResult: {
        requestId: 'op-a01', customerId: 1, outcome: 'STARTED', classification: {},
        workflow: { workflowId: 20, workItemId: 21, taskCode: 'TRANSACTION_SEARCH', status: 'ACTIVE' },
        loanInquiry: null, executionStatus: null, taskAction: null,
        answer: '업무를 준비했습니다.', answerStatus: 'GENERATED',
      },
      financialOperationCompleted: false, agentMessageSaved: true,
    }
    expect(replyFromAgentRun(run)).toEqual({
      kind: 'started', taskCode: 'TRANSACTION_SEARCH', workItemId: 21, answer: '업무를 준비했습니다.',
    })
  })
})
