import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import './staff.css'

function BrandMark() {
  return <svg width="30" height="30" viewBox="0 0 32 32" aria-hidden="true"><rect x="1" y="1" width="30" height="30" rx="9" fill="#2E70C3"></rect><path d="M8 22 L12.5 10 L17 22 M9.8 17.5 H15.2" stroke="#FFFFFF" strokeWidth="2.4" fill="none" strokeLinecap="round" strokeLinejoin="round"></path><path d="M19 13 L24.5 21.5 M24.5 13 L19 21.5" stroke="#F2B27F" strokeWidth="2.4" strokeLinecap="round"></path></svg>
}

const MENU = [
  { to: '/staff', end: true, label: '대시보드', icon: <><rect x="3" y="3" width="7" height="9" rx="1.5"></rect><rect x="14" y="3" width="7" height="5" rx="1.5"></rect><rect x="14" y="12" width="7" height="9" rx="1.5"></rect><rect x="3" y="16" width="7" height="5" rx="1.5"></rect></> },
  { to: '/staff/queue', end: false, label: '사전검토 업무함', icon: <><path d="M8 6h13M8 12h13M8 18h13"></path><circle cx="4" cy="6" r="1"></circle><circle cx="4" cy="12" r="1"></circle><circle cx="4" cy="18" r="1"></circle></> },
  { to: '/staff/branch', end: false, label: '지점 예약 업무함', icon: <><rect x="3" y="5" width="18" height="16" rx="2"></rect><path d="M3 10h18M8 3v4M16 3v4"></path></> },
  { to: '/staff/policy-changes', end: false, label: '업무기준 변경', icon: <><path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"></path><path d="M14 3v6h6M8 13h8M8 17h5"></path></> },
]

/** Staff chrome from ui-reference 62 (topbar) and 63 (sidebar). Only the authenticated user's own name/role is shown. */
export function StaffLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const onLogout = async () => {
    try { await logout() } finally { navigate('/login') }
  }

  return <div className="staff-app">
    <header className="staff-topbar">
      <NavLink to="/staff" end className="staff-topbar__brand"><BrandMark /><strong>AX뱅크</strong></NavLink>
      <span aria-hidden="true" className="staff-topbar__divider"></span>
      <span className="staff-topbar__system">사전접수 업무 시스템</span>
      <span className="staff-topbar__badge"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="5" y="11" width="14" height="10" rx="2"></rect><path d="M8 11V8a4 4 0 0 1 8 0v3"></path></svg>직원 전용</span>
      <div className="staff-topbar__spacer"></div>
      <div className="staff-topbar__user">
        <span aria-hidden="true" className="staff-topbar__avatar">{user?.name.slice(0, 1) ?? '직'}</span>
        <div><strong>{user?.name ?? '직원'} 님</strong><span>{user?.role === 'ADMIN' ? '관리자' : '직원'}</span></div>
      </div>
    </header>
    <div className="staff-body">
      <nav aria-label="직원 메뉴" className="staff-sidebar">
        <span className="staff-sidebar__label">업무</span>
        {MENU.map(item => <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => `staff-sidebar__link${isActive ? ' staff-sidebar__link--active' : ''}`}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{item.icon}</svg>{item.label}
        </NavLink>)}
        <div className="staff-sidebar__spacer"></div>
        <span aria-hidden="true" className="staff-sidebar__rule"></span>
        <button type="button" className="staff-sidebar__link" onClick={() => void onLogout()}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><path d="M16 17l5-5-5-5M21 12H9"></path></svg>로그아웃
        </button>
      </nav>
      <main className="staff-main"><Outlet /></main>
    </div>
  </div>
}
