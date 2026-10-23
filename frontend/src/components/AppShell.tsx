import { Link, Outlet } from 'react-router-dom'

export function AppShell() {
  return <main className="app-shell"><header><Link to="/">AX뱅크</Link><span>교육용 시연 화면 · 가상 데이터</span><nav><Link to="/login">로그인</Link><Link to="/chat/demo">AI 금융 도우미</Link><Link to="/staff">직원 화면</Link></nav></header><Outlet /></main>
}
