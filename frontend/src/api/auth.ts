import { ApiError, apiFetch } from './client'
import { authMock } from './mock/authMock'

export type AuthRole = 'CUSTOMER' | 'STAFF' | 'ADMIN'
export type AuthUser = { userId: number; name: string; role: AuthRole }

export interface AuthApi {
  login(loginId: string, password: string): Promise<AuthUser>
  logout(): Promise<void>
  me(): Promise<AuthUser>
}

function requireData<T>(data: T | null): T {
  if (data === null || data === undefined) throw new ApiError('INVALID_RESPONSE', 200, 'Response data is missing.')
  return data
}

const realAuthApi: AuthApi = {
  async login(loginId, password) {
    const body = await apiFetch<AuthUser>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify({ loginId, password }) })
    return requireData(body.data)
  },
  async logout() {
    try {
      await apiFetch<null>('/api/v1/auth/logout', { method: 'POST' })
    } catch (error) {
      // The logout response body is not fixed by the contract; an empty 2xx is a success.
      if (!(error instanceof ApiError && error.code === 'INVALID_RESPONSE')) throw error
    }
  },
  async me() {
    const body = await apiFetch<AuthUser>('/api/v1/auth/me')
    return requireData(body.data)
  },
}

export const authApi: AuthApi = import.meta.env.VITE_API_MOCK_AUTH === 'false' ? realAuthApi : authMock
