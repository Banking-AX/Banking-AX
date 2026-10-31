import { ApiError } from '../client'
import type { AuthApi, AuthSession, AuthUser } from '../auth'

// Mock only: replaced by the Spring session API when VITE_API_MOCK_AUTH=false.
// Matches the representative test customer in the DB seed; the real password is stored as a BCrypt hash.
const STORAGE_KEY = 'bankingax.mock.auth'
const DELAY_MS = 300
const ACCOUNTS = new Map<string, { password: string; user: AuthUser }>([
  ['customer_demo', { password: 'demo1234', user: { userId: 1, name: '김민수', role: 'CUSTOMER' } }],
])

function delay() {
  return new Promise<void>(resolve => setTimeout(resolve, DELAY_MS))
}

// Only the loginId is stored; user details are rebuilt from ACCOUNTS so stale sessions cannot restore old values.
function readLoginId(): string | null {
  try {
    return sessionStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function writeLoginId(loginId: string | null) {
  try {
    if (loginId) sessionStorage.setItem(STORAGE_KEY, loginId)
    else sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // Storage may be unavailable (private mode); the mock then keeps no session.
  }
}

export const authMock: AuthApi = {
  async login(loginId, password) {
    await delay()
    const account = ACCOUNTS.get(loginId)
    if (!account || account.password !== password) {
      throw new ApiError('AUTH_INVALID_CREDENTIALS', 401, 'Invalid credentials.')
    }
    writeLoginId(loginId)
    return mockSession(account.user)
  },
  async refresh() {
    const user = await authMock.me()
    return mockSession(user)
  },
  async logout() {
    await delay()
    writeLoginId(null)
  },
  async me() {
    await delay()
    const loginId = readLoginId()
    const account = loginId ? ACCOUNTS.get(loginId) : undefined
    if (!account) throw new ApiError('AUTH_EXPIRED', 401, 'Not authenticated.')
    return account.user
  },
}

function mockSession(user: AuthUser): AuthSession {
  return { ...user, accessToken: null, tokenType: 'Mock', expiresIn: 0 }
}
