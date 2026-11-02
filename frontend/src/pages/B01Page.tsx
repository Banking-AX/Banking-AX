import { useMemo } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { createMockB01Adapter } from '../api/mock/b01Mock'
import { useAuth } from '../auth/AuthContext'
import { SiteFooter } from '../components/SiteFooter'
import { SiteHeader } from '../components/SiteHeader'
import { B01Flow } from '../features/b01/B01Flow'

// Mock wiring. The real task screen will pass a server-backed B01Adapter to B01Flow.
export function B01Page() {
  const { workItemId } = useParams()
  const location = useLocation()
  const { user } = useAuth()
  const adapter = useMemo(() => createMockB01Adapter(), [])

  if (!workItemId) {
    return <div className="b01-page">
      <SiteHeader />
      <main className="b01-main">
        <section className="b01-card">
          <h1 className="b01-card__title">찾을 수 없는 업무 화면이에요.</h1>
          <Link to="/" className="b01-button b01-button--primary">개인뱅킹 메인으로</Link>
        </section>
      </main>
      <SiteFooter />
    </div>
  }

  // A new navigation to this URL (e.g. "서명 취소" from the sign step) restarts the flow.
  return <B01Flow key={location.key} adapter={adapter} workItemId={workItemId} signerName={user?.name ?? ''} />
}
