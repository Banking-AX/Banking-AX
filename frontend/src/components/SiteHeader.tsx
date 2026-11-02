import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

// Menus without a route keep the design's appearance but do not navigate.
const MAIN_MENU = ['조회', '이체', '금융상품', '공과금/법원', '뱅킹보안관리', '기타금융', '부가서비스', '사용자관리', '카드/증권']

export function SiteHeader() {
  const { user, status, logout } = useAuth()
  const navigate = useNavigate()

  const onLogout = async () => {
    try {
      await logout()
    } finally {
      navigate('/login')
    }
  }

  return <header className="site-header">
    <div className="site-header__inner">
      <div className="site-header__top">
        <Link to="/" className="site-header__brand">
          <svg width="32" height="32" viewBox="0 0 32 32" aria-hidden="true"><rect x="1" y="1" width="30" height="30" rx="9" fill="#FFFFFF"></rect><path d="M8 22 L12.5 10 L17 22 M9.8 17.5 H15.2" stroke="#2E70C3" strokeWidth="2.4" fill="none" strokeLinecap="round" strokeLinejoin="round"></path><path d="M19 13 L24.5 21.5 M24.5 13 L19 21.5" stroke="#C9682A" strokeWidth="2.4" strokeLinecap="round"></path></svg>
          <span className="site-header__brand-name">AX뱅크</span>
        </Link>
        <span className="site-header__demo-tag">교육용 시연</span>
        <nav aria-label="고객 구분" className="site-header__segment">
          <Link to="/" aria-current="page" className="site-header__segment-link site-header__segment-link--active">개인</Link>
          <a className="site-header__segment-link" aria-disabled="true">기업</a>
          <a className="site-header__segment-link" aria-disabled="true">금융상품</a>
        </nav>
        <div className="site-header__spacer"></div>
        <nav aria-label="부가 메뉴" className="site-header__utility">
          <a className="site-header__utility-link" aria-disabled="true">금융서비스</a>
          <a className="site-header__utility-link" aria-disabled="true">고객센터</a>
          <a className="site-header__utility-link" aria-disabled="true">은행소개</a>
        </nav>
        <span aria-hidden="true" className="site-header__divider"></span>
        {status === 'authenticated' && user
          ? <div className="site-header__account">
            <span className="site-header__user"><strong>{user.name}</strong>님</span>
            <Link to="/work" className="site-header__pill">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="5" y="4" width="14" height="17" rx="2"></rect><path d="M9 4h6v3H9zM9 12h6M9 16h4"></path></svg>
              업무 요청 기록</Link>
            <a className="site-header__bell" aria-label="알림" aria-disabled="true">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"></path><path d="M10 20a2 2 0 0 0 4 0"></path></svg>
            </a>
            <button type="button" className="site-header__logout" onClick={onLogout}>로그아웃</button>
          </div>
          : status === 'anonymous' && <Link to="/login" className="site-header__pill">로그인</Link>}
        <button type="button" aria-label="통합검색" className="site-header__icon-button">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"></circle><path d="M20 20l-3.5-3.5"></path></svg>
        </button>
      </div>
      <div className="site-header__bottom">
        <nav aria-label="주 메뉴" className="site-header__main-menu">
          {MAIN_MENU.map(label => <a key={label} className="site-header__main-link" aria-disabled="true">{label}</a>)}
        </nav>
        <button type="button" aria-label="전체 메뉴" className="site-header__all-menu">
          <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16"></path></svg>
        </button>
      </div>
    </div>
  </header>
}
