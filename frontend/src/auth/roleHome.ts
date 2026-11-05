import type { AuthRole } from '../api/auth'

const STAFF_PATH = /^\/staff(?:[/?#]|$)/

export function isStaffPath(path: string): boolean {
  return STAFF_PATH.test(path)
}

/** Landing page for a role; route guards remain the authority on access. */
export function homeFor(role: AuthRole): string {
  return role === 'STAFF' ? '/staff' : '/'
}

/** Keeps a safe returnTo only when it belongs to the role's own area. */
export function postLoginPath(role: AuthRole, returnTo: string): string {
  if (role === 'ADMIN') return returnTo
  if (role === 'STAFF') return isStaffPath(returnTo) ? returnTo : homeFor(role)
  return isStaffPath(returnTo) ? homeFor(role) : returnTo
}
