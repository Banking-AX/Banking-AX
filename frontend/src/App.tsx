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
import { VisitReservationPage } from './pages/VisitReservationPage'
import { SignPage } from './pages/SignPage'

export function App() {
  return <Routes>
    <Route path="/" element={<MainPage />} />
    <Route path="/login" element={<LoginPage />} />
    <Route path="/sign/:docKey" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><SignPage /></RequireAuth>} />
    <Route element={<AppShell />}>
      <Route path="/chat/:roomId?" element={<RequireAuth from="chat" roles={['CUSTOMER', 'ADMIN']}><ChatShellPage /></RequireAuth>} />
      <Route path="/tasks/c01" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><DebtAdjustmentPage /></RequireAuth>} />
      <Route path="/tasks/c02/:workItemId" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><C02IntakePage /></RequireAuth>} />
      <Route path="/tasks/c02/notifications" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><C02NotificationsPage /></RequireAuth>} />
      <Route path="/tasks/c02/reservations" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><VisitReservationPage /></RequireAuth>} />
      <Route path="/staff/c02/:caseId" element={<RequireAuth roles={['STAFF', 'ADMIN']}><C02StaffReviewPage /></RequireAuth>} />
      <Route path="/tasks/a01/:workItemId" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><TransactionSearchPage /></RequireAuth>} />
      <Route path="/tasks/b02/:workItemId" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><HousingSavingsClosePage /></RequireAuth>} />
      <Route path="/staff/*" element={<RequireAuth roles={['STAFF', 'ADMIN']}><StaffShellPage /></RequireAuth>} />
    </Route>
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes>
}
