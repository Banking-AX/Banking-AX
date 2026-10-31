import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'
import type { AuthRole } from '../api/auth'

export function RequireAuth({ from, roles, children }:
  { from?: 'chat'; roles?: AuthRole[]; children: ReactNode }) {
  const { status, user } = useAuth()
  const location = useLocation()

  if (status === 'loading') return <div role="status" aria-live="polite">로그인 상태를 확인하고 있어요.</div>
  if (status === 'anonymous') {
    const returnTo = encodeURIComponent(`${location.pathname}${location.search}`)
    return <Navigate to={`/login?${from ? `from=${from}&` : ''}returnTo=${returnTo}`} replace />
  }
  if (roles && user && !roles.includes(user.role)) return <Navigate to="/" replace />
  return <>{children}</>
}
