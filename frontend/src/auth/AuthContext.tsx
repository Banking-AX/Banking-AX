import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { authApi, type AuthSession, type AuthUser } from '../api/auth'
import { setAccessToken, setAuthenticationLostHandler, setRefreshAccessTokenHandler } from '../api/client'

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

type AuthContextValue = {
  user: AuthUser | null
  accessToken: string | null
  status: AuthStatus
  login(loginId: string, password: string): Promise<AuthUser>
  logout(): Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const [user, setUser] = useState<AuthUser | null>(null)
  const [accessTokenState, setAccessTokenState] = useState<string | null>(null)
  const [status, setStatus] = useState<AuthStatus>('loading')
  const userRef = useRef<AuthUser | null>(null)
  // A late bootstrap refresh must never clear a session established by login.
  const sessionGeneration = useRef(0)

  const applyUser = useCallback((next: AuthUser | null, token: string | null = null) => {
    userRef.current = next
    setUser(next)
    setAccessToken(token)
    setAccessTokenState(token)
    setStatus(next ? 'authenticated' : 'anonymous')
  }, [])

  const applySession = useCallback((session: AuthSession) => {
    applyUser({ userId: session.userId, name: session.name, role: session.role }, session.accessToken)
  }, [applyUser])

  useEffect(() => {
    let active = true
    const bootstrapGeneration = sessionGeneration.current
    // The HttpOnly refresh cookie is the only real-mode bootstrap credential.
    authApi.refresh()
      .then(session => { if (active && sessionGeneration.current === bootstrapGeneration) applySession(session) })
      .catch(() => { if (active && sessionGeneration.current === bootstrapGeneration) applyUser(null) })
    return () => { active = false }
  }, [applySession, applyUser])

  useEffect(() => {
    setRefreshAccessTokenHandler(async () => {
      const session = await authApi.refresh()
      applySession(session)
      return session.accessToken
    })
    setAuthenticationLostHandler(() => {
      if (!userRef.current) return
      sessionGeneration.current += 1
      applyUser(null)
      navigate('/login?reason=expired', { replace: true })
    })
    return () => {
      setRefreshAccessTokenHandler(null)
      setAuthenticationLostHandler(null)
    }
  }, [applySession, applyUser, navigate])

  const login = useCallback(async (loginId: string, password: string) => {
    const session = await authApi.login(loginId, password)
    sessionGeneration.current += 1
    applySession(session)
    return { userId: session.userId, name: session.name, role: session.role }
  }, [applySession])

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } finally {
      sessionGeneration.current += 1
      applyUser(null)
    }
  }, [applyUser])

  const value = useMemo(() => ({ user, accessToken: accessTokenState, status, login, logout }),
    [user, accessTokenState, status, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthProvider')
  return context
}
