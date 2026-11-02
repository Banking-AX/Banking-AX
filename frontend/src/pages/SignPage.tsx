import { useMemo } from 'react'
import { Link, useParams } from 'react-router-dom'
import { isSignDocKey } from '../api/sign'
import { createMockSignAdapter } from '../api/mock/signMock'
import { useAuth } from '../auth/AuthContext'
import { SiteFooter } from '../components/SiteFooter'
import { SiteHeader } from '../components/SiteHeader'
import { SignFlow } from '../features/sign/SignFlow'
import { getSignSetup } from '../features/sign/signFixtures'

// Mock demo route. Real task pages render SignFlow directly with their task adapter.
export function SignPage() {
  const { docKey } = useParams()
  const { user } = useAuth()
  const validKey = isSignDocKey(docKey) ? docKey : null
  const adapter = useMemo(() => validKey ? createMockSignAdapter(validKey) : null, [validKey])
  const setup = validKey ? getSignSetup(validKey, user?.name ?? '') : null

  if (!validKey || !adapter || !setup) {
    return <div className="sign-page">
      <SiteHeader />
      <main className="sign-main">
        <section className="sign-card sign-card--narrow">
          <h1 className="sign-card__title">찾을 수 없는 서명 화면이에요.</h1>
          <Link to="/" className="sign-button sign-button--primary sign-button--link">개인뱅킹 메인으로</Link>
        </section>
      </main>
      <SiteFooter />
    </div>
  }

  // key resets the whole flow when navigating between documents.
  return <SignFlow key={validKey} adapter={adapter} {...setup} />
}
