import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { authApi, type AuthUser } from '../api/auth'
import { setUnauthorizedHandler } from '../api/client'

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

type AuthContextValue = {
  user: AuthUser | null
  status: AuthStatus
  login(loginId: string, password: string): Promise<AuthUser>
  logout(): Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const [user, setUser] = useState<AuthUser | null>(null)
  const [status, setStatus] = useState<AuthStatus>('loading')
  const userRef = useRef<AuthUser | null>(null)

  const applyUser = useCallback((next: AuthUser | null) => {
    userRef.current = next
    setUser(next)
    setStatus(next ? 'authenticated' : 'anonymous')
  }, [])

  useEffect(() => {
    let active = true
    // Any failure (401, 404, network) restores as anonymous without surfacing an error.
    authApi.me()
      .then(me => { if (active) applyUser(me) })
      .catch(() => { if (active) applyUser(null) })
    return () => { active = false }
  }, [applyUser])

  useEffect(() => {
    setUnauthorizedHandler(() => {
      // Only an established session can expire; an anonymous 401 is not an expiry.
      if (!userRef.current) return
      applyUser(null)
      navigate('/login?reason=expired', { replace: true })
    })
    return () => setUnauthorizedHandler(null)
  }, [applyUser, navigate])

  const login = useCallback(async (loginId: string, password: string) => {
    const next = await authApi.login(loginId, password)
    applyUser(next)
    return next
  }, [applyUser])

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } finally {
      applyUser(null)
    }
  }, [applyUser])

  const value = useMemo(() => ({ user, status, login, logout }), [user, status, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthProvider')
  return context
}
