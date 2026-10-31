import { ApiError, apiFetch, apiFetchWithoutAuthRetry } from './client'
import { authMock } from './mock/authMock'

export type AuthRole = 'CUSTOMER' | 'STAFF' | 'ADMIN'
export type AuthUser = { userId: number; name: string; role: AuthRole }
export type AuthSession = AuthUser & { accessToken: string | null; tokenType: 'Bearer' | 'Mock'; expiresIn: number }

export interface AuthApi {
  login(loginId: string, password: string): Promise<AuthSession>
  refresh(): Promise<AuthSession>
  logout(): Promise<void>
  me(): Promise<AuthUser>
}

function requireData<T>(data: T | null): T {
  if (data === null || data === undefined) throw new ApiError('INVALID_RESPONSE', 200, 'Response data is missing.')
  return data
}

export function readCookie(name: string, source = typeof document === 'undefined' ? '' : document.cookie): string | null {
  const prefix = `${encodeURIComponent(name)}=`
  for (const part of source.split(';')) {
    const item = part.trim()
    if (item.startsWith(prefix)) return decodeURIComponent(item.slice(prefix.length))
  }
  return null
}

function csrfHeaders(): HeadersInit {
  const token = readCookie('XSRF-TOKEN')
  if (!token) throw new ApiError('AUTH_CSRF_MISSING', 403, 'CSRF token cookie is missing.')
  return { 'X-XSRF-TOKEN': token }
}

let realRefreshInFlight: Promise<AuthSession> | null = null

function refreshRealSession(): Promise<AuthSession> {
  if (!realRefreshInFlight) {
    realRefreshInFlight = apiFetchWithoutAuthRetry<AuthSession>('/api/v1/auth/refresh', {
      method: 'POST', headers: csrfHeaders(),
    }).then(body => requireData(body.data)).finally(() => { realRefreshInFlight = null })
  }
  return realRefreshInFlight
}

const realAuthApi: AuthApi = {
  async login(loginId, password) {
    const body = await apiFetchWithoutAuthRetry<AuthSession>('/api/v1/auth/login', {
      method: 'POST', body: JSON.stringify({ loginId, password }),
    })
    return requireData(body.data)
  },
  async refresh() {
    return refreshRealSession()
  },
  async logout() {
    await apiFetchWithoutAuthRetry<null>('/api/v1/auth/logout', {
      method: 'POST', headers: csrfHeaders(),
    })
  },
  async me() {
    const body = await apiFetch<AuthUser>('/api/v1/auth/me')
    return requireData(body.data)
  },
}

export const usesMockAuth = import.meta.env.VITE_API_MOCK_AUTH !== 'false'
export const authApi: AuthApi = usesMockAuth ? authMock : realAuthApi
