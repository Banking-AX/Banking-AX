import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { SiteFooter } from '../components/SiteFooter'
import { SiteHeader } from '../components/SiteHeader'

const ARROW_CIRCLE = <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#6B7280" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M10.5 8.5L14 12l-3.5 3.5"></path></svg>

const SERVICES = [
  { label: '전계좌조회', icon: <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="#2E70C3" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="3" y="6" width="13" height="10" rx="2"></rect><circle cx="16" cy="15" r="4" stroke="#C9682A"></circle><path d="M19 18l2.5 2.5" stroke="#C9682A"></path></svg> },
  { label: '계좌이체', icon: <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="#2E70C3" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="3" y="7" width="12" height="10" rx="2"></rect><path d="M13 12h8M18 9l3 3-3 3" stroke="#C9682A"></path></svg> },
  { label: '간편조회서비스', icon: <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="#2E70C3" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="3" y="4" width="18" height="12" rx="2"></rect><path d="M9 20h6M12 16v4"></path><path d="M9 10l2 2 4-4" stroke="#C9682A"></path></svg> },
  { label: '지로납부', icon: <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="#2E70C3" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="3" y="6" width="18" height="13" rx="2"></rect><path d="M3 8l9 6 9-6"></path><circle cx="18" cy="5" r="3" stroke="#C9682A"></circle></svg> },
]

const PRODUCTS = [
  { label: '예금/신탁', icon: <><rect x="5" y="3" width="14" height="18" rx="2"></rect><path d="M8 8h8M8 12h8M8 16h5"></path></> },
  { label: '펀드', icon: <><path d="M4 20V4M4 20h16"></path><path d="M7 15l4-5 3 3 5-6"></path></> },
  { label: '대출', icon: <><circle cx="12" cy="12" r="8"></circle><path d="M8 9l2 6 2-5 2 5 2-6M7.5 11.5h9"></path></> },
  { label: '외환', icon: <><ellipse cx="12" cy="6" rx="7" ry="2.5"></ellipse><path d="M5 6v6c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5V6M5 12v6c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5v-6"></path></> },
  { label: '골드/실버', icon: <><circle cx="12" cy="12" r="8"></circle><path d="M15 9.5a3.5 3.5 0 1 0 0 5h-2.5v-2"></path></> },
  { label: '퇴직연금', icon: <><path d="M12 3a8 8 0 0 1 8 8H4a8 8 0 0 1 8-8z"></path><path d="M12 11v7a2 2 0 0 1-4 0"></path></> },
]

export function MainPage() {
  const { status } = useAuth()
  const navigate = useNavigate()
  // Connection point for the chat modal in step 4; nothing renders from it yet.
  const [chatOpen, setChatOpen] = useState(false)

  const startChat = () => {
    if (status === 'authenticated') setChatOpen(true)
    else if (status === 'anonymous') navigate('/login?from=chat&returnTo=/')
  }
  const loading = status === 'loading'

  return <div className="main-page">
    <SiteHeader />

    <main className="main-content">
      <section aria-label="AI 금융 도우미 소개" className="main-hero">
        <div className="main-container main-hero__inner">
          <div className="main-hero__copy">
            <p className="main-hero__eyebrow">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" aria-hidden="true"><path d="M12 3l1.8 4.7L18.5 9.5l-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8z"></path></svg>
              NEW · AI 금융 도우미</p>
            <h1 className="main-hero__title">복잡한 금융업무,<br /><span className="main-hero__title-accent">이제 AI에게 말하세요.</span></h1>
            <p className="main-hero__lead">조회부터 금융업무 신청까지, AI 금융 도우미가 함께합니다.</p>
            <div className="main-hero__actions">
              <button type="button" onClick={startChat} disabled={loading} className="main-hero__cta">AI 금융업무 시작하기
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6"></path></svg>
              </button>
              <a className="main-hero__news" aria-disabled="true">새소식 보기
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M10.5 8.5L14 12l-3.5 3.5"></path></svg>
              </a>
            </div>
          </div>

          <div aria-hidden="true" className="main-hero__art">
            <div className="main-art">
              <div className="main-art__board"><div className="main-art__board-screen"></div></div>
              <div className="main-art__dot main-art__dot--large"></div>
              <div className="main-art__dot main-art__dot--small"></div>
              <div className="main-art__robot">
                <svg width="104" height="104" viewBox="0 0 104 104" fill="none">
                  <line x1="52" y1="14" x2="52" y2="26" stroke="#2E70C3" strokeWidth="4" strokeLinecap="round"></line>
                  <circle cx="52" cy="11" r="6" fill="#C9682A"></circle>
                  <rect x="18" y="26" width="68" height="56" rx="22" fill="#2E70C3"></rect>
                  <rect x="28" y="38" width="48" height="30" rx="14" fill="#EEF6FE"></rect>
                  <circle cx="42" cy="52" r="5" fill="#1E3A5F"></circle>
                  <circle cx="62" cy="52" r="5" fill="#1E3A5F"></circle>
                  <path d="M45 61 q7 5 14 0" stroke="#1E3A5F" strokeWidth="3" strokeLinecap="round"></path>
                  <rect x="10" y="44" width="8" height="18" rx="4" fill="#4A78BE"></rect>
                  <rect x="86" y="44" width="8" height="18" rx="4" fill="#4A78BE"></rect>
                </svg>
              </div>
              <div className="main-art__chat">
                <div className="main-art__chat-head"><span className="main-art__chat-avatar"></span>AI 금융 도우미</div>
                <div className="main-art__chat-body">
                  <div className="main-art__bubble main-art__bubble--agent">어떤 금융업무를 도와드릴까요?</div>
                  <div className="main-art__bubble main-art__bubble--user">거래내역 조회하고 채무조정도 신청할게요</div>
                  <div className="main-art__tasks">
                    <div className="main-art__task"><span>거래내역 조회</span><span className="main-art__badge main-art__badge--done">완료</span></div>
                    <div className="main-art__task"><span>채무조정 접수</span><span className="main-art__badge main-art__badge--wait">대기</span></div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section aria-label="로그인 및 인증" className="main-auth">
        <div className="main-container main-auth__inner">
          <div className="main-tiles">
            {status === 'anonymous' && <Link to="/login" className="main-tile main-tile--primary">
              <div className="main-tile__content main-tile__content--login">
                <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4"></path><path d="M4 12h11M11 8l4 4-4 4"></path></svg>
                <span className="main-tile__title">뱅킹로그인</span>
              </div>
            </Link>}
            {status === 'authenticated' && <a className="main-tile main-tile--primary" aria-disabled="true">
              <div className="main-tile__content main-tile__content--requests">
                <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="5" y="4" width="14" height="17" rx="2"></rect><path d="M9 4h6v3H9zM9 12h6M9 16h4"></path></svg>
                <span className="main-tile__title">업무 요청 기록</span>
              </div>
            </a>}
            {loading && <div className="main-tile main-tile--primary" aria-hidden="true"></div>}
            <a className="main-tile main-tile--secondary" aria-disabled="true">
              <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6z"></path><path d="M9 12l2 2 4-4"></path></svg>
              <span className="main-tile__title">인증센터</span>
            </a>
          </div>
          <div className="main-cert-links">
            <a className="main-cert-link" aria-disabled="true">인증서 발급/재발급
              {ARROW_CIRCLE}
            </a>
            <a className="main-cert-link" aria-disabled="true">타행/타기관 인증서 등록
              {ARROW_CIRCLE}
            </a>
          </div>
        </div>
      </section>

      <section className="main-services">
        <div className="main-container main-services__inner">
          <div className="main-services__group">
            <div className="main-services__head">
              <h2 className="main-section-title">주요서비스</h2>
              <a className="main-services__more" aria-disabled="true">더보기 ›</a>
            </div>
            <div className="main-services__grid">
              {SERVICES.map(item => <a key={item.label} className="main-service" aria-disabled="true">
                <span className="main-service__icon">{item.icon}</span>
                <span className="main-service__label">{item.label}</span>
              </a>)}
            </div>
          </div>

          <div className="main-services__group">
            <h2 className="main-section-title">금융상품</h2>
            <div className="main-products">
              {PRODUCTS.map(item => <a key={item.label} className="main-product" aria-disabled="true">
                <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#6B7280" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{item.icon}</svg>{item.label}</a>)}
            </div>
          </div>
        </div>
      </section>
    </main>

    <SiteFooter />

    <button type="button" onClick={startChat} disabled={loading} className="main-fab">
      <span className="main-fab__icon">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="#2E70C3" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M4 6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H10l-4 3v-3H6a2 2 0 0 1-2-2z"></path><path d="M12 7.5l.9 2.1 2.1.9-2.1.9-.9 2.1-.9-2.1-2.1-.9 2.1-.9z" fill="#2E70C3"></path></svg>
      </span>
      AI 금융 도우미
    </button>
  </div>
}
