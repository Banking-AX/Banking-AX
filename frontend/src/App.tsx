import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { ChatShellPage, LoginPage, StaffShellPage } from './pages/ShellPages'
import { DebtAdjustmentPage } from './features/c01/DebtAdjustmentPage'

export function App() {
  return <Routes>
    <Route element={<AppShell />}>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/chat/:roomId?" element={<ChatShellPage />} />
      <Route path="/tasks/c01" element={<DebtAdjustmentPage />} />
      <Route path="/staff/*" element={<StaffShellPage />} />
      <Route path="/" element={<ChatShellPage />} />
    </Route>
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes>
}
