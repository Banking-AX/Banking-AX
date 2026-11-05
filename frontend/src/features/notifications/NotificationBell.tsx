import { useEffect } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { taskBffEnabled } from '../../api/taskApi'
import type { AuthRole } from '../../api/auth'
import { badgeText } from './notificationView'
import { refreshUnreadCount, useUnreadCount } from './unreadStore'
import './notifications.css'

/** Header bell (NOTI-11/04/12/16): links to /notifications and re-reads the unread count on each route change. */
export function NotificationBell({ role }: Readonly<{ role: AuthRole }>) {
  const { pathname } = useLocation()
  const unread = useUnreadCount()
  const enabled = taskBffEnabled && (role === 'CUSTOMER' || role === 'ADMIN')

  useEffect(() => { if (enabled) void refreshUnreadCount() }, [enabled, pathname])

  const badge = enabled && unread !== null ? badgeText(unread) : null
  return <Link to="/notifications" className="site-header__bell" aria-label={badge ? `알림, 읽지 않은 알림 ${unread}건` : '알림'}>
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"></path><path d="M10 20a2 2 0 0 0 4 0"></path></svg>
    {badge && <span aria-hidden="true" className="site-header__bell-badge">{badge}</span>}
  </Link>
}
