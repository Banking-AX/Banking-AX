import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { ChatShellPage } from './pages/ShellPages'
import { StaffLayout } from './features/staff/StaffLayout'
import { StaffBranchPage, StaffDashboardPage, StaffQueuePage } from './features/staff/StaffPages'
import { LoginPage } from './pages/LoginPage'
import { MainPage } from './pages/MainPage'
import { RequireAuth } from './auth/RequireAuth'
import { useAuth } from './auth/AuthContext'
import { ChatModal } from './features/chat/ChatModal'
import { ChatDockProvider } from './features/chat/chatDockContext'
import { DebtAdjustmentPage } from './features/c01/DebtAdjustmentPage'
import { C02IntakePage } from './pages/C02IntakePage'
import { C02CaseDetailPage } from './pages/C02CaseDetailPage'
import { C02CaseListPage } from './pages/C02CaseListPage'
import { C02SignPage } from './pages/C02SignPage'
import { C02StaffReviewPage } from './pages/C02StaffReviewPage'
import { NotificationsPage } from './features/notifications/NotificationsPage'
import { TransactionSearchPage } from './features/a01/TransactionSearchPage'
import { HousingSavingsClosePage } from './features/b02/HousingSavingsClosePage'
import { VisitReservationPage } from './pages/VisitReservationPage'
import { SignPage } from './pages/SignPage'
import { B01Page } from './pages/B01Page'
import { ChatBPreviewPage } from './pages/ChatBPreviewPage'
import { ChatRoomsPreviewPage } from './pages/ChatRoomsPreviewPage'
import { TaskGuidePreviewPage } from './pages/TaskGuidePreviewPage'
import { WorkRecordsPage } from './pages/WorkRecordsPage'
import { WorkRecordDetailPage } from './pages/WorkRecordDetailPage'
import { LoanAccountsPage } from './pages/LoanAccountsPage'

export function App() {
  const { status, user } = useAuth()
  // The chat stays mounted while a customer is signed in so the conversation survives page changes.
  return <ChatDockProvider>
    <Routes>
    <Route path="/" element={<MainPage />} />
    <Route path="/login" element={<LoginPage />} />
    <Route path="/sign/:docKey" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><SignPage /></RequireAuth>} />
    <Route path="/tasks/b01/:workItemId" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><B01Page /></RequireAuth>} />
    <Route path="/tasks/c02/cases/:caseId/sign" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><C02SignPage /></RequireAuth>} />
    <Route path="/work" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><WorkRecordsPage /></RequireAuth>} />
    <Route path="/work/:recordId" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><WorkRecordDetailPage /></RequireAuth>} />
    <Route path="/loans" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><LoanAccountsPage /></RequireAuth>} />
    {import.meta.env.DEV && <Route path="/preview/chat-b" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><ChatBPreviewPage /></RequireAuth>} />}
    {import.meta.env.DEV && <Route path="/preview/task-guide" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><TaskGuidePreviewPage /></RequireAuth>} />}
    {import.meta.env.DEV && <Route path="/preview/chat-rooms" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><ChatRoomsPreviewPage /></RequireAuth>} />}
    <Route element={<AppShell />}>
      <Route path="/chat/:roomId?" element={<RequireAuth from="chat" roles={['CUSTOMER', 'ADMIN']}><ChatShellPage /></RequireAuth>} />
      <Route path="/tasks/c01" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><DebtAdjustmentPage /></RequireAuth>} />
      <Route path="/tasks/c02" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><C02CaseListPage /></RequireAuth>} />
      <Route path="/tasks/c02/:workItemId" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><C02IntakePage /></RequireAuth>} />
      <Route path="/tasks/c02/cases/:caseId" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><C02CaseDetailPage /></RequireAuth>} />
      <Route path="/notifications" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><NotificationsPage /></RequireAuth>} />
      <Route path="/tasks/c02/notifications" element={<Navigate to="/notifications" replace />} />
      <Route path="/tasks/c02/reservations" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><VisitReservationPage /></RequireAuth>} />
      <Route path="/tasks/a01/:workItemId" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><TransactionSearchPage /></RequireAuth>} />
      <Route path="/tasks/b02/:workItemId" element={<RequireAuth roles={['CUSTOMER', 'ADMIN']}><HousingSavingsClosePage /></RequireAuth>} />
    </Route>
    <Route path="/staff" element={<RequireAuth roles={['STAFF', 'ADMIN']}><StaffLayout /></RequireAuth>}>
      <Route index element={<StaffDashboardPage />} />
      <Route path="queue" element={<StaffQueuePage />} />
      <Route path="branch" element={<StaffBranchPage />} />
      <Route path="c02/:caseId" element={<C02StaffReviewPage />} />
      <Route path="*" element={<Navigate to="/staff" replace />} />
    </Route>
    <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
    {status === 'authenticated' && user?.role === 'CUSTOMER' && <ChatModal />}
  </ChatDockProvider>
}
