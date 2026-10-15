import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router'
import { AppShell } from '@/components/AppShell'
import { RequireAuth } from '@/components/RequireAuth'
import { RequireGymTenant, RequirePermission, RequirePlatform } from '@/components/RequireTenant'
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
import { MembershipPlanMembersPage, MembershipsPage } from '@/features/memberships/MembershipsPage'
import { AnnouncementsPage, NotificationTemplatesPage, NotificationsPage } from '@/features/notifications/NotificationsPage'
import { NotFoundPage } from '@/features/NotFoundPage'
import { PaymentsPage } from '@/features/payments/PaymentsPage'
import { PlansPage } from '@/features/plans/PlansPage'
import { PlatformActuatorPage } from '@/features/platform/PlatformActuatorPage'
import { PlatformGymsPage } from '@/features/platform/PlatformGymsPage'
import { PlatformStaffPasswordPage } from '@/features/platform/PlatformStaffPasswordPage'
import { ProfilePage } from '@/features/profile/ProfilePage'
import { ReportsPage } from '@/features/reports/ReportsPage'
import { ReviewPage } from '@/features/reviews/ReviewPage'
import { SettingsPage } from '@/features/settings/SettingsPage'
import { RolesPage } from '@/features/users/RolesPage'
import { UsersPage } from '@/features/users/UsersPage'
import { ApiError } from '@/lib/api'
import { AuthProvider, useAuth } from '@/lib/auth'
import { DEFAULT_GYM_SLUG } from '@/lib/brand'
import { FEATURES } from '@/lib/features'
import { GymSlugFromRoute } from '@/lib/GymSlug'
import { isPlatformSuperAdmin, platformHomePath } from '@/lib/platform'
import { AboutPage, FacilitiesPage, ServicesPage } from '@/public/ContentPages'
import { ContactPage } from '@/public/ContactPage'
import { HomePage } from '@/public/HomePage'
import { MembershipPlansPage } from '@/public/MembershipPlansPage'
import { PublicLayout } from '@/public/PublicLayout'
import { RootLandingPage } from '@/public/RootLandingPage'
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

function needs(anyOf: string[], page: ReactNode) {
  return <RequirePermission anyOf={anyOf}>{page}</RequirePermission>
}

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
  { path: 'members', element: needs(['MEMBER_VIEW'], <MembersPage />) },
  { path: 'members/new', element: needs(['MEMBER_CREATE'], <MemberNewPage />) },
  { path: 'members/:id', element: needs(['MEMBER_VIEW'], <MemberDetailPage />) },
  { path: 'members/:id/edit', element: needs(['MEMBER_UPDATE'], <MemberEditPage />) },
  { path: 'plans', element: needs(['MEMBERSHIP_VIEW'], <PlansPage />) },
  { path: 'memberships', element: needs(['MEMBERSHIP_VIEW'], <MembershipsPage />) },
  { path: 'memberships/:planId', element: needs(['MEMBERSHIP_VIEW'], <MembershipPlanMembersPage />) },
  { path: 'payments', element: needs(['PAYMENT_VIEW'], <PaymentsPage />) },
  { path: 'attendance', element: needs(['ATTENDANCE_VIEW'], <AttendancePage />) },
  { path: 'attendance/live', element: needs(['ATTENDANCE_VIEW'], <AttendanceLivePage />) },
  { path: 'devices', element: needs(['DEVICE_VIEW'], <DevicesPage />) },
  { path: 'review', element: needs(['DEVICE_VIEW'], <ReviewPage />) },
  { path: 'devices/new', element: needs(['DEVICE_MANAGE'], <DeviceNewPage />) },
  { path: 'devices/:id', element: needs(['DEVICE_VIEW'], <DeviceDetailPage />) },
  { path: 'devices/:id/:section', element: needs(['DEVICE_VIEW'], <DeviceDetailPage />) },
  ...(FEATURES.enquiries ? [{ path: 'enquiries', element: needs(['ENQUIRY_VIEW'], <EnquiriesPage />) }] : []),
  { path: 'reports', element: needs(['REPORT_VIEW'], <ReportsPage />) },
  { path: 'reports/attendance', element: <Navigate to="/app/attendance" replace /> },
  { path: 'reports/payments', element: <Navigate to="/app/payments" replace /> },
  { path: 'reports/devices', element: <Navigate to="/app/devices" replace /> },
  ...(FEATURES.notifications
    ? [
        { path: 'notifications', element: needs(['NOTIFICATION_SEND'], <NotificationsPage />) },
        { path: 'notifications/templates', element: needs(['NOTIFICATION_SEND'], <NotificationTemplatesPage />) },
        { path: 'announcements', element: needs(['NOTIFICATION_SEND'], <AnnouncementsPage />) },
      ]
    : []),
  { path: 'users', element: needs(['USER_MANAGE'], <UsersPage />) },
  { path: 'roles', element: needs(['ROLE_MANAGE', 'USER_MANAGE'], <RolesPage />) },
  { path: 'settings', element: needs(['SETTINGS_MANAGE'], <SettingsPage />) },
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
          { path: 'audit', element: needs(['AUDIT_VIEW'], <AuditPage />) },
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
