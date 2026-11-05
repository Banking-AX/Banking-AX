import { afterEach, describe, expect, it, vi } from 'vitest'
import { setAccessToken } from './client'
import { searchTransactions } from './taskApi'

afterEach(() => {
  vi.unstubAllGlobals()
  setAccessToken(null)
})

describe('A-01 task BFF request', () => {
  it('uses the authenticated common client and sends the Bearer token to Spring', async () => {
    setAccessToken('customer-access-token')
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer customer-access-token')
      return new Response(JSON.stringify({ requestId: 'a01-test', taskCode: 'TRANSACTION_SEARCH', workItemId: 6, caseId: null, revision: null, data: { transactions: [] }, error: null }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    await searchTransactions({ requestId: 'a01-test', workItemId: 6, accountId: 1, startDate: '2026-10-01', endDate: '2026-10-31', transactionType: null, minimumAmount: null, maximumAmount: null, keyword: null, sortOrder: 'RECENT', page: 0, size: 20 })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/customer/a01/transaction-search')
  })
})
