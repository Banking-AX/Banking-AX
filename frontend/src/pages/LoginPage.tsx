import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { toUserMessage } from '../api/errorMessages'
import { useAuth } from '../auth/AuthContext'
import { isStaffPath, postLoginPath } from '../auth/roleHome'
import { SiteFooter } from '../components/SiteFooter'
import { SiteHeader } from '../components/SiteHeader'

type Tab = 'id' | 'cert'
type FormError = { kind: 'empty' } | { kind: 'credentials'; message: string } | { kind: 'server'; message: string }

const EMPTY_MESSAGE = '이용자 ID와 비밀번호를 모두 입력해 주세요.'

// Internal paths only: "/x" is allowed, "//host" and "/\host" are treated as external.
function safeReturnTo(value: string | null): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) return '/'
  return value
}

export function LoginPage() {
  const [params] = useSearchParams()
  const expired = params.get('reason') === 'expired'
  const fromChat = params.get('from') === 'chat'
  const returnTo = safeReturnTo(params.get('returnTo'))
  const { login } = useAuth()
  const navigate = useNavigate()

  const [tab, setTab] = useState<Tab>('id')
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState<FormError | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [successName, setSuccessName] = useState<string | null>(null)
  const [successPath, setSuccessPath] = useState(returnTo)

  const idInvalid = error?.kind === 'credentials' || (error?.kind === 'empty' && !loginId)
  const pwInvalid = error?.kind === 'credentials' || (error?.kind === 'empty' && !password)
  const errorMessage = error ? (error.kind === 'empty' ? EMPTY_MESSAGE : error.message) : ''

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (submitting) return
    if (!loginId || !password) { setError({ kind: 'empty' }); return }
    setSubmitting(true)
    try {
      const user = await login(loginId, password)
      setError(null)
      setPassword('')
      setSuccessName(user.name)
      const target = postLoginPath(user.role, returnTo)
      setSuccessPath(target)
      navigate(target, { replace: true })
    } catch (cause) {
      const message = toUserMessage(cause)
      setError(cause instanceof ApiError && cause.code === 'AUTH_INVALID_CREDENTIALS' ? { kind: 'credentials', message } : { kind: 'server', message })
    } finally {
      setSubmitting(false)
    }
  }

  const pickId = () => setTab('id')
  const pickCert = () => { setTab('cert'); setSuccessName(null) }

  return <div className="login-page">
    <SiteHeader />

    <main className="login-main">
      <div className="login-intro">
        <h1 className="login-title">개인뱅킹 로그인</h1>
        <p className="login-subtitle">AX뱅크 개인뱅킹과 AI 금융 도우미를 이용하려면 로그인해 주세요.</p>
      </div>

      {expired && <div role="alert" className="login-banner login-banner--expired">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true" className="login-banner__icon"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v5l3 2"></path></svg>
        <span><strong>로그인 시간이 만료되어 자동으로 로그아웃되었어요.</strong><br />개인정보 보호를 위해 업무 요청 기록·알림·업무 화면은 다시 로그인해야 볼 수 있어요. 진행 중이던 업무와 대화는 그대로 남아 있어요.</span>
      </div>}

      {fromChat && <div role="status" className="login-banner login-banner--chat">
        <span className="login-banner__avatar"><svg width="24" height="24" viewBox="0 0 104 104" fill="none" aria-hidden="true"><line x1="52" y1="14" x2="52" y2="26" stroke="#FFFFFF" strokeWidth="6" strokeLinecap="round"></line><circle cx="52" cy="11" r="8" fill="#F2B27F"></circle><rect x="16" y="26" width="72" height="58" rx="24" fill="#FFFFFF"></rect><rect x="28" y="40" width="48" height="30" rx="14" fill="#DDECFD"></rect><circle cx="42" cy="54" r="6" fill="#1E3A5F"></circle><circle cx="62" cy="54" r="6" fill="#1E3A5F"></circle></svg></span>
        <span><strong>AI 금융 도우미에서 요청한 업무를 계속하려면 로그인이 필요해요.</strong><br />로그인하면 보던 대화로 바로 돌아갑니다.</span>
      </div>}

      <section aria-label="로그인" className="login-card">
        <div role="tablist" aria-label="로그인 방식" className="login-tabs">
          <button type="button" role="tab" aria-selected={tab === 'id'} onClick={pickId} className={`login-tab${tab === 'id' ? ' login-tab--active' : ''}`}>이용자 ID 로그인</button>
          <button type="button" role="tab" aria-selected={tab === 'cert'} onClick={pickCert} className={`login-tab${tab === 'cert' ? ' login-tab--active' : ''}`}>인증서 로그인</button>
        </div>

        <div className="login-card__body">
          {tab === 'id' && !successName && <form onSubmit={onSubmit} noValidate className="login-form">
            <div className="login-field">
              <label htmlFor="login-id" className="login-label">이용자 ID</label>
              <input id="login-id" type="text" autoComplete="username" value={loginId} onChange={e => setLoginId(e.target.value)} aria-invalid={idInvalid} placeholder="이용자 ID를 입력해 주세요" className={`login-input${idInvalid ? ' login-input--invalid' : ''}`} />
            </div>
            <div className="login-field">
              <label htmlFor="login-pw" className="login-label">비밀번호</label>
              <div className="login-password">
                <input id="login-pw" type={showPassword ? 'text' : 'password'} autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} aria-invalid={pwInvalid} aria-describedby="login-err" placeholder="비밀번호를 입력해 주세요" className={`login-input login-input--password${pwInvalid ? ' login-input--invalid' : ''}`} />
                <button type="button" onClick={() => setShowPassword(v => !v)} aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'} className="login-password__toggle"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"></path><circle cx="12" cy="12" r="3"></circle></svg></button>
              </div>
              {error && <span id="login-err" role="alert" className="login-error"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v6M12 16.5h.01"></path></svg>{errorMessage}</span>}
            </div>
            <button type="submit" disabled={submitting} aria-busy={submitting} className="login-submit">로그인</button>
            <p className="login-help">시연용으로 미리 등록된 계정만 사용해요. 회원가입과 아이디·비밀번호 찾기는 제공하지 않아요.</p>
            <a className="login-staff-link" aria-disabled="true">직원이신가요? 직원 로그인</a>
          </form>}

          {tab === 'cert' && <div className="login-cert">
            <div aria-hidden="true" className="login-cert__preview">
              <div className="login-cert__item"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#6B7280" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="4" y="5" width="16" height="12" rx="2"></rect><path d="M8 21l4-4 4 4"></path></svg><span className="login-cert__name">[인증서 이름]</span><span className="login-cert__expiry">만료일 [YYYY.MM.DD]</span></div>
              <div className="login-cert__field"></div>
            </div>
            <p className="login-cert__notice"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true" className="login-cert__notice-icon"><circle cx="12" cy="12" r="9"></circle><path d="M12 11v5M12 7.5h.01"></path></svg>인증서 로그인은 화면 참고용이에요. 이 시연에서는 이용자 ID 로그인만 사용할 수 있어요.</p>
            <button type="button" disabled className="login-cert__submit">인증서로 로그인</button>
            <button type="button" onClick={pickId} className="login-cert__switch">이용자 ID로 로그인하기</button>
          </div>}

          {tab === 'id' && successName && <div role="status" className="login-success">
            <span className="login-success__icon"><svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12l5 5 9-10"></path></svg></span>
            <strong className="login-success__title">{successName}님, 로그인되었어요</strong>
            <span className="login-success__message">{isStaffPath(successPath) ? '직원 업무 화면으로 이동합니다.' : fromChat ? '보던 AI 대화로 돌아가 요청한 업무를 이어서 진행해요.' : '개인뱅킹 메인으로 이동합니다.'}</span>
            <Link to={successPath} className="login-success__action">{isStaffPath(successPath) ? '직원 업무 화면으로' : fromChat ? '대화로 돌아가기' : '개인뱅킹 메인으로'}</Link>
          </div>}
        </div>
      </section>

      <div className="login-notice">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#6B7280" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="login-notice__icon"><path d="M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6z"></path></svg>
        <span><strong className="login-notice__strong">교육용 시연 화면입니다.</strong> 실제 은행 아이디나 비밀번호를 입력하지 마세요. 시연용 테스트 계정: <span className="login-notice__account">customer_demo / demo-customer-pass</span></span>
      </div>
    </main>

    <SiteFooter />
  </div>
}
