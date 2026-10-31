import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  apiFetch,
  setAccessToken,
  setAuthenticationLostHandler,
  setRefreshAccessTokenHandler,
} from './client'
import { readCookie } from './auth'

const envelope = (data: unknown, error: unknown = null) => JSON.stringify({
  requestId: 'test', taskCode: 'TEST', workItemId: null, caseId: null, revision: null, data, error,
})

afterEach(() => {
  vi.unstubAllGlobals()
  setAccessToken(null)
  setRefreshAccessTokenHandler(null)
  setAuthenticationLostHandler(null)
})

describe('authenticated API client', () => {
  it('shares one refresh while concurrent 401 requests retry once with the replacement token', async () => {
    let refreshCalls = 0
    const authorizations: string[] = []
    setAccessToken('expired-access')
    setRefreshAccessTokenHandler(async () => {
      refreshCalls += 1
      await Promise.resolve()
      return 'replacement-access'
    })
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const authorization = new Headers(init?.headers).get('Authorization') ?? ''
      authorizations.push(authorization)
      if (authorization === 'Bearer expired-access') {
        return new Response(envelope(null, {
          code: 'AUTH_REQUIRED', message: 'expired', retryable: false, traceId: 'trace',
        }), { status: 401, headers: { 'Content-Type': 'application/json' } })
      }
      return new Response(envelope({ ok: true }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      })
    }))

    const [first, second] = await Promise.all([
      apiFetch<{ ok: boolean }>('/api/v1/customer/one'),
      apiFetch<{ ok: boolean }>('/api/v1/customer/two'),
    ])

    expect(first.data?.ok).toBe(true)
    expect(second.data?.ok).toBe(true)
    expect(refreshCalls).toBe(1)
    expect(authorizations.filter(value => value === 'Bearer replacement-access')).toHaveLength(2)
  })

  it('clears authentication without an infinite retry when refresh fails', async () => {
    const lost = vi.fn()
    setAccessToken('expired-access')
    setRefreshAccessTokenHandler(async () => { throw new Error('refresh failed') })
    setAuthenticationLostHandler(lost)
    const fetchMock = vi.fn(async () => new Response(envelope(null, {
      code: 'AUTH_REQUIRED', message: 'expired', retryable: false, traceId: 'trace',
    }), { status: 401, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiFetch('/api/v1/customer/protected')).rejects.toMatchObject({ code: 'AUTH_REQUIRED' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(lost).toHaveBeenCalledTimes(1)
  })

  it('does not send the customer JWT to FastAPI agent routes', async () => {
    setAccessToken('customer-access')
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      expect(new Headers(init?.headers).has('Authorization')).toBe(false)
      return new Response(envelope({ ok: true }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    await apiFetch('/api/v1/customer-agent/messages/start')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe('CSRF cookie parsing', () => {
  it('reads and decodes XSRF-TOKEN without exposing the HttpOnly refresh token', () => {
    expect(readCookie('XSRF-TOKEN', 'theme=light; XSRF-TOKEN=a%2Fb%2Bc; other=value')).toBe('a/b+c')
    expect(readCookie('BANKING_AX_REFRESH', 'XSRF-TOKEN=csrf-only')).toBeNull()
  })
})
