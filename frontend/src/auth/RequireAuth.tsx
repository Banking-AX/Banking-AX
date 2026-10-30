import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'

export function RequireAuth({ from, children }: { from?: 'chat'; children: ReactNode }) {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'loading') return null
  if (status === 'anonymous') {
    const returnTo = encodeURIComponent(`${location.pathname}${location.search}`)
    return <Navigate to={`/login?${from ? `from=${from}&` : ''}returnTo=${returnTo}`} replace />
  }
  return <>{children}</>
}
