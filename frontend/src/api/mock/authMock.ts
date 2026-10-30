import { ApiError } from '../client'
import type { AuthApi, AuthUser } from '../auth'

// Mock only: replaced by the Spring session API when VITE_API_MOCK_AUTH=false.
const STORAGE_KEY = 'bankingax.mock.auth'
const DELAY_MS = 300
const DEMO_LOGIN_ID = 'minjun_demo'
const DEMO_PASSWORD = 'demo1234'
const DEMO_USER: AuthUser = { userId: 1, name: '김민준', role: 'CUSTOMER' }

function delay() {
  return new Promise<void>(resolve => setTimeout(resolve, DELAY_MS))
}

function readUser(): AuthUser | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) as AuthUser : null
  } catch {
    return null
  }
}

function writeUser(user: AuthUser | null) {
  try {
    if (user) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(user))
    else sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // Storage may be unavailable (private mode); the mock then keeps no session.
  }
}

export const authMock: AuthApi = {
  async login(loginId, password) {
    await delay()
    if (loginId !== DEMO_LOGIN_ID || password !== DEMO_PASSWORD) {
      throw new ApiError('AUTH_INVALID_CREDENTIALS', 401, 'Invalid credentials.')
    }
    writeUser(DEMO_USER)
    return DEMO_USER
  },
  async logout() {
    await delay()
    writeUser(null)
  },
  async me() {
    await delay()
    const user = readUser()
    if (!user) throw new ApiError('AUTH_EXPIRED', 401, 'Not authenticated.')
    return user
  },
}
