import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router'
import { AppShell } from '@/components/AppShell'
import { RequireAuth } from '@/components/RequireAuth'
import { AttendanceLivePage } from '@/features/attendance/AttendanceLivePage'
import { AttendancePage } from '@/features/attendance/AttendancePage'
import { ForgotPasswordPage } from '@/features/auth/ForgotPasswordPage'
import { LoginPage } from '@/features/auth/LoginPage'
import { DashboardPage } from '@/features/dashboard/DashboardPage'
import { DeviceDetailPage } from '@/features/devices/DeviceDetailPage'
import { DeviceNewPage } from '@/features/devices/DeviceNewPage'
import { DevicesPage } from '@/features/devices/DevicesPage'
import { MemberDetailPage } from '@/features/members/MemberDetailPage'
import { MemberEditPage } from '@/features/members/MemberEditPage'
import { MemberNewPage } from '@/features/members/MemberNewPage'
import { MembersPage } from '@/features/members/MembersPage'
import { MembershipsPage } from '@/features/memberships/MembershipsPage'
import { NotFoundPage } from '@/features/NotFoundPage'
import { PaymentsPage } from '@/features/payments/PaymentsPage'
import { PlansPage } from '@/features/plans/PlansPage'
import { ProfilePage } from '@/features/profile/ProfilePage'
import { ApiError } from '@/lib/api'
import { AuthProvider } from '@/lib/auth'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      retry: (count, error) => {
        if (error instanceof ApiError && error.status < 500) return false
        return count < 1
      },
    },
  },
})

const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/app/dashboard" replace /> },
  { path: '/app/login', element: <LoginPage /> },
  { path: '/app/forgot-password', element: <ForgotPasswordPage /> },
  {
    path: '/app',
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        children: [
          { index: true, element: <Navigate to="dashboard" replace /> },
          { path: 'dashboard', element: <DashboardPage /> },
          { path: 'members', element: <MembersPage /> },
          { path: 'members/new', element: <MemberNewPage /> },
          { path: 'members/:id', element: <MemberDetailPage /> },
          { path: 'members/:id/edit', element: <MemberEditPage /> },
          { path: 'plans', element: <PlansPage /> },
          { path: 'memberships', element: <MembershipsPage /> },
          { path: 'payments', element: <PaymentsPage /> },
          { path: 'attendance', element: <AttendancePage /> },
          { path: 'attendance/live', element: <AttendanceLivePage /> },
          { path: 'devices', element: <DevicesPage /> },
          { path: 'devices/new', element: <DeviceNewPage /> },
          { path: 'devices/:id', element: <DeviceDetailPage /> },
          { path: 'devices/:id/:section', element: <DeviceDetailPage /> },
          { path: 'profile', element: <ProfilePage /> },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
)
