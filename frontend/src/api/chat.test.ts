import { afterEach, describe, expect, it, vi } from 'vitest'
import { b01ReconnectNextStep, chatApi, replyFromAgentRun, replyFromChatMessage, type AgentRun, type B01ReconnectResult, type ChatMessage, type ChatRoom } from './chat'
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

const reconnectAction = {
  type: 'SELECT_B01_RECONNECT', allowStartNew: false,
  candidates: [
    { workItemId: 18, status: 'ACTIVE', originalChatRoomId: 5, originalChatRoomAvailable: true },
    { workItemId: 3, status: 'ON_HOLD', originalChatRoomId: null, originalChatRoomAvailable: false },
  ],
}

const reconnectRun = (action: unknown): AgentRun => ({
  customerMessage: message(40, 1, 'CUSTOMER', '충당순서 변경 신청할래'),
  agentMessage: message(41, 2, 'AGENT', '진행 중인 충당순서 변경 업무가 있습니다.'),
  agentResult: {
    requestId: 'op-reconnect', customerId: 1, outcome: 'B01_RECONNECT_CONFIRMATION_REQUIRED', classification: {},
    workflow: null, loanInquiry: null, executionStatus: null, taskAction: null, b01ReconnectAction: action,
    answer: '진행 중인 충당순서 변경 업무가 있습니다.', answerStatus: 'GENERATED',
  },
  financialOperationCompleted: false, agentMessageSaved: true,
})

const reconnectResult = (overrides: Partial<B01ReconnectResult> = {}): B01ReconnectResult => ({
  outcome: 'EXISTING_SELECTED', workItemId: 18, workItemStatus: 'ACTIVE',
  originalChatRoomId: 5, originalChatRoomAvailable: true, navigationTarget: 'ORIGINAL_CHAT', ...overrides,
})

describe('B-01 reconnect action', () => {
  it('shows ACTIVE and ON_HOLD candidates from the first agent answer', () => {
    expect(replyFromAgentRun(reconnectRun(reconnectAction))).toEqual({
      kind: 'b01Reconnect', answer: '진행 중인 충당순서 변경 업무가 있습니다.', action: reconnectAction,
    })
  })

  it('restores the same candidates from persisted citations after a reload', () => {
    const agent = message(41, 2, 'AGENT', '진행 중인 충당순서 변경 업무가 있습니다.')
    agent.citations = JSON.stringify({ b01ReconnectAction: reconnectAction })

    expect(replyFromChatMessage(agent)).toEqual({ kind: 'b01Reconnect', answer: agent.content, action: reconnectAction })
  })

  it('ignores malformed actions instead of guessing buttons', () => {
    const invalid = [
      { ...reconnectAction, type: 'OTHER' },
      { ...reconnectAction, candidates: [] },
      { ...reconnectAction, candidates: [{ ...reconnectAction.candidates[0], status: 'DONE' }] },
      { ...reconnectAction, candidates: [reconnectAction.candidates[0], reconnectAction.candidates[0]] },
      { ...reconnectAction, candidates: [{ ...reconnectAction.candidates[0], originalChatRoomId: null }] },
      { ...reconnectAction, allowStartNew: 'true' },
    ]
    for (const action of invalid) {
      expect(replyFromAgentRun(reconnectRun(action)).kind).toBe('message')
      const agent = message(41, 2, 'AGENT', '안내')
      agent.citations = JSON.stringify({ b01ReconnectAction: action })
      expect(replyFromChatMessage(agent)).toEqual({ kind: 'message', answer: '안내' })
    }
  })

  it('keeps the existing B-01 taskAction and A-02 uiAction when no reconnect action is stored', () => {
    const agent = message(42, 2, 'AGENT', '신청 화면에서 진행해 주세요.')
    agent.citations = JSON.stringify({ taskAction: {
      taskCode: 'REPAYMENT_ORDER_CHANGE', label: '신청 화면으로 이동', actionRequestId: 'action-1',
    } })
    expect(replyFromChatMessage(agent).kind).toBe('taskAction')
    agent.citations = JSON.stringify({ uiAction: { type: 'OPEN_CUSTOMER_LOANS', taskCode: 'LOAN_INQUIRY' } })
    expect(replyFromChatMessage(agent).kind).toBe('loanDetailsAction')
  })

  it('posts the chosen workItemId to the room reconnect API without a customer id', async () => {
    setAccessToken('customer-token')
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
      new Response(envelope(reconnectResult()), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const request = { requestId: 'b01-reconnect-001', choice: 'CONTINUE_EXISTING', workItemId: 18 } as const
    await expect(chatApi.reconnectB01(9, request)).resolves.toEqual(reconnectResult())
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/customer/chat/rooms/9/b01/reconnect')
    expect(fetchMock.mock.calls[0][1]?.method).toBe('POST')
    const body = JSON.parse(String(fetchMock.mock.calls[0][1]?.body))
    expect(body).toEqual(request)
    expect(body).not.toHaveProperty('customerId')
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Authorization')).toBe('Bearer customer-token')
  })

  it('sends START_NEW with a null workItemId', async () => {
    setAccessToken('customer-token')
    const created = reconnectResult({ outcome: 'NEW_CREATED', workItemId: 50, originalChatRoomId: 9, navigationTarget: 'START_B01_GRAPH' })
    const fetchMock = vi.fn(async () => new Response(envelope(created), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(chatApi.reconnectB01(9, { requestId: 'b01-reconnect-002', choice: 'START_NEW', workItemId: null })).resolves.toEqual(created)
    expect(JSON.parse(String((fetchMock.mock.calls[0] as unknown[])[1] && ((fetchMock.mock.calls[0] as unknown[])[1] as RequestInit).body))).toEqual({
      requestId: 'b01-reconnect-002', choice: 'START_NEW', workItemId: null,
    })
  })

  it('surfaces the server rejection of a foreign or finished work item', async () => {
    setAccessToken('customer-token')
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      requestId: null, taskCode: null, workItemId: null, caseId: null, revision: null, data: null,
      error: { code: 'WORK_ITEM_NOT_AVAILABLE', message: 'The B-01 work item is not available.', retryable: false, traceId: 't' },
    }), { status: 400 })))

    await expect(chatApi.reconnectB01(9, { requestId: 'r', choice: 'CONTINUE_EXISTING', workItemId: 999 }))
      .rejects.toMatchObject({ code: 'WORK_ITEM_NOT_AVAILABLE', status: 400 })
  })

  it('rejects responses whose outcome and navigation target disagree', async () => {
    setAccessToken('customer-token')
    for (const bad of [
      reconnectResult({ navigationTarget: 'START_B01_GRAPH' }),
      reconnectResult({ outcome: 'NEW_CREATED', navigationTarget: 'ORIGINAL_CHAT' }),
      reconnectResult({ originalChatRoomId: null }),
      { ...reconnectResult(), navigationTarget: 'https://example.invalid' },
    ]) {
      vi.stubGlobal('fetch', vi.fn(async () => new Response(envelope(bad), { status: 200 })))
      await expect(chatApi.reconnectB01(9, { requestId: 'r', choice: 'CONTINUE_EXISTING', workItemId: 18 }))
        .rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    }
  })

  it('maps each outcome to an in-app step built from ids', () => {
    expect(b01ReconnectNextStep(reconnectResult())).toEqual({ type: 'openRoom', roomId: 5, workItemId: 18 })
    expect(b01ReconnectNextStep(reconnectResult({ originalChatRoomAvailable: false, navigationTarget: 'B01_TASK' })))
      .toEqual({ type: 'openTask', workItemId: 18 })
    for (const outcome of ['NEW_CREATED', 'SAME_CHAT_REUSED', 'REQUEST_REPLAYED'] as const) {
      expect(b01ReconnectNextStep(reconnectResult({ outcome, workItemId: 50, navigationTarget: 'START_B01_GRAPH' })))
        .toEqual({ type: 'startGraph', workItemId: 50 })
    }
    expect(b01ReconnectNextStep(reconnectResult({ outcome: 'ACTIVE_WORK_ITEM_CONFLICT' })))
      .toEqual({ type: 'conflict', workItemId: 18 })
  })
})
