import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { ChatShellPage, StaffShellPage } from './pages/ShellPages'
import { LoginPage } from './pages/LoginPage'
import { MainPage } from './pages/MainPage'
import { RequireAuth } from './auth/RequireAuth'
import { DebtAdjustmentPage } from './features/c01/DebtAdjustmentPage'
import { C02IntakePage } from './pages/C02IntakePage'
import { C02StaffReviewPage } from './pages/C02StaffReviewPage'
import { C02NotificationsPage } from './pages/C02NotificationsPage'
import { TransactionSearchPage } from './features/a01/TransactionSearchPage'
import { HousingSavingsClosePage } from './features/b02/HousingSavingsClosePage'

export function App() {
  return <Routes>
    <Route path="/" element={<MainPage />} />
    <Route path="/login" element={<LoginPage />} />
    <Route element={<AppShell />}>
      <Route path="/chat/:roomId?" element={<RequireAuth from="chat"><ChatShellPage /></RequireAuth>} />
      <Route path="/tasks/c01" element={<DebtAdjustmentPage />} />
      <Route path="/tasks/c02/:workItemId" element={<C02IntakePage />} />
      <Route path="/tasks/c02/notifications" element={<C02NotificationsPage />} />
      <Route path="/staff/c02/:caseId" element={<C02StaffReviewPage />} />
      <Route path="/tasks/a01/:workItemId" element={<TransactionSearchPage />} />
      <Route path="/tasks/b02/:workItemId" element={<HousingSavingsClosePage />} />
      <Route path="/staff/*" element={<StaffShellPage />} />
    </Route>
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes>
}
