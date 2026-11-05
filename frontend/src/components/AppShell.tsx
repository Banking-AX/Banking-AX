import { Outlet, useLocation } from 'react-router-dom'
import { SiteFooter } from './SiteFooter'
import { SiteHeader } from './SiteHeader'

/** Customer task routes reuse the approved banking chrome; staff keeps its separate shell. */
export function AppShell() {
  const { pathname } = useLocation()
  if (pathname.startsWith('/staff')) return <main className="app-shell app-shell--staff"><Outlet /></main>
  return <div className="task-shell"><SiteHeader /><Outlet /><SiteFooter /></div>
}
