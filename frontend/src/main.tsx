import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router'
import { AppShell } from '@/components/AppShell'
import { RequireAuth } from '@/components/RequireAuth'
import { RequireGymTenant, RequirePlatform } from '@/components/RequireTenant'
import { AuditPage } from '@/features/audit/AuditPage'
import { AttendanceLivePage } from '@/features/attendance/AttendanceLivePage'
import { AttendancePage } from '@/features/attendance/AttendancePage'
import { ForgotPasswordPage } from '@/features/auth/ForgotPasswordPage'
import { LoginPage } from '@/features/auth/LoginPage'
import { DashboardPage } from '@/features/dashboard/DashboardPage'
import { DeviceDetailPage } from '@/features/devices/DeviceDetailPage'
import { DeviceNewPage } from '@/features/devices/DeviceNewPage'
import { DevicesPage } from '@/features/devices/DevicesPage'
import { EnquiriesPage } from '@/features/enquiries/EnquiriesPage'
import { MemberDetailPage } from '@/features/members/MemberDetailPage'
import { MemberEditPage } from '@/features/members/MemberEditPage'
import { MemberNewPage } from '@/features/members/MemberNewPage'
import { MembersPage } from '@/features/members/MembersPage'
import { MembershipsPage } from '@/features/memberships/MembershipsPage'
import { NotificationsPage } from '@/features/notifications/NotificationsPage'
import { NotificationTemplatesPage } from '@/features/notifications/NotificationTemplatesPage'
import { NotFoundPage } from '@/features/NotFoundPage'
import { PaymentsPage } from '@/features/payments/PaymentsPage'
import { PlansPage } from '@/features/plans/PlansPage'
import { PlatformActuatorPage } from '@/features/platform/PlatformActuatorPage'
import { PlatformGymsPage } from '@/features/platform/PlatformGymsPage'
import { PlatformStaffPasswordPage } from '@/features/platform/PlatformStaffPasswordPage'
import { ProfilePage } from '@/features/profile/ProfilePage'
import { DeviceReportPage, MembershipReportPage, ReportsPage } from '@/features/reports/ReportsPage'
import { SettingsPage } from '@/features/settings/SettingsPage'
import { RolesPage } from '@/features/users/RolesPage'
import { UsersPage } from '@/features/users/UsersPage'
import { ApiError } from '@/lib/api'
import { AuthProvider, useAuth } from '@/lib/auth'
import { DEFAULT_GYM_SLUG } from '@/lib/brand'
import { GymSlugFromRoute } from '@/lib/GymSlug'
import { isPlatformSuperAdmin, platformHomePath } from '@/lib/platform'
import { AboutPage, FacilitiesPage, ServicesPage } from '@/public/ContentPages'
import { ContactPage } from '@/public/ContactPage'
import { HomePage } from '@/public/HomePage'
import { MembershipPlansPage } from '@/public/MembershipPlansPage'
import { PublicLayout } from '@/public/PublicLayout'
import { RootLandingPage } from '@/public/RootLandingPage'
import './index.css'
import { WhatsAppNotificationSettingsPage } from '@/features/settings/whatsappSettings/WhatsAppNotificationSettingsPage'
import { NotificationHistoryPage } from './features/notifications/NotificationHistoryPage'
import { NotificationReportPage } from '@/features/reports/NotificationReportPage'


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

function AppIndexRedirect() {
  const { user } = useAuth()
  if (isPlatformSuperAdmin(user)) {
    return <Navigate to={platformHomePath()} replace />
  }
  return <Navigate to="dashboard" replace />
}

const publicChildren = [
  { index: true, element: <HomePage /> },
  { path: 'about', element: <AboutPage /> },
  { path: 'services', element: <ServicesPage /> },
  { path: 'facilities', element: <FacilitiesPage /> },
  { path: 'membership-plans', element: <MembershipPlansPage /> },
  { path: 'contact', element: <ContactPage /> },
]

const gymChildren = [
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
  { path: 'enquiries', element: <EnquiriesPage /> },
  { path: 'reports', element: <ReportsPage /> },
  { path: 'reports/memberships', element: <MembershipReportPage /> },
  { path: 'reports/attendance', element: <Navigate to="/app/attendance" replace /> },
  { path: 'reports/payments', element: <Navigate to="/app/payments" replace /> },
  { path: 'reports/devices', element: <DeviceReportPage /> },
  { path: 'reports/notifications', element: <NotificationReportPage /> },
  { path: 'notifications', element: <NotificationsPage /> },
  { path: 'notifications/templates', element: <NotificationTemplatesPage /> },
  { path: 'notifications/history', element: <NotificationHistoryPage /> },
  // { path: 'announcements', element: <AnnouncementsPage /> },
  { path: 'users', element: <UsersPage /> },
  { path: 'roles', element: <RolesPage /> },
  { path: 'settings', element: <SettingsPage /> },

  {
    path: 'settings/whatsapp',
    element: <WhatsAppNotificationSettingsPage />,
  },
]

const router = createBrowserRouter([
  {
    path: '/',
    element: DEFAULT_GYM_SLUG ? <Navigate to={`/g/${DEFAULT_GYM_SLUG}`} replace /> : <RootLandingPage />,
  },
  {
    path: '/g/:gymSlug',
    element: (
      <GymSlugFromRoute>
        <PublicLayout />
      </GymSlugFromRoute>
    ),
    children: publicChildren,
  },
  { path: '/app/login', element: <LoginPage /> },
  { path: '/app/forgot-password', element: <ForgotPasswordPage /> },
  {
    path: '/app',
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        children: [
          { index: true, element: <AppIndexRedirect /> },
          {
            element: <RequirePlatform />,
            children: [
              { path: 'platform/gyms', element: <PlatformGymsPage /> },
              { path: 'platform/staff-passwords', element: <PlatformStaffPasswordPage /> },
              { path: 'platform/actuator', element: <PlatformActuatorPage /> },
            ],
          },
          // Audit is useful for both platform (all tenants) and gym admins.
          { path: 'audit', element: <AuditPage /> },
          { path: 'profile', element: <ProfilePage /> },
          {
            element: <RequireGymTenant />,
            children: gymChildren,
          },
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
