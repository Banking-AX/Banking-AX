import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { ChatShellPage, LoginPage, StaffShellPage } from './pages/ShellPages'
import { DebtAdjustmentPage } from './features/c01/DebtAdjustmentPage'
import { C02IntakePage } from './pages/C02IntakePage'
import { TransactionSearchPage } from './features/a01/TransactionSearchPage'

export function App() {
  return <Routes>
    <Route element={<AppShell />}>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/chat/:roomId?" element={<ChatShellPage />} />
      <Route path="/tasks/c01" element={<DebtAdjustmentPage />} />
      <Route path="/tasks/c02/:workItemId" element={<C02IntakePage />} />
      <Route path="/tasks/a01/:workItemId" element={<TransactionSearchPage />} />
      <Route path="/staff/*" element={<StaffShellPage />} />
      <Route path="/" element={<ChatShellPage />} />
    </Route>
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes>
}
