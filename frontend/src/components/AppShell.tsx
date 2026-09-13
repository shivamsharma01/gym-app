import { NavLink, Outlet, useNavigate } from 'react-router'
import {
  Activity,
  BarChart3,
  Bell,
  Building2,
  ClipboardList,
  CreditCard,
  Inbox,
  LayoutDashboard,
  LogOut,
  Menu,
  MonitorSmartphone,
  Settings,
  Shield,
  Users,
  Wallet,
  X,
} from 'lucide-react'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button } from '@/components/ui'
import { useAuth } from '@/lib/auth'
import { brandDisplayName, brandLogo, type PublicSite } from '@/lib/brand'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useStaffLive } from '@/lib/live'

const links = [
  { to: '/app/dashboard', label: 'Dashboard', icon: LayoutDashboard, perm: null },
  { to: '/app/members', label: 'Members', icon: Users, perm: 'MEMBER_VIEW' },
  { to: '/app/plans', label: 'Plans', icon: CreditCard, perm: 'MEMBERSHIP_VIEW' },
  { to: '/app/memberships', label: 'Memberships', icon: CreditCard, perm: 'MEMBERSHIP_VIEW' },
  { to: '/app/payments', label: 'Payments', icon: Wallet, perm: 'PAYMENT_VIEW' },
  { to: '/app/attendance', label: 'Attendance', icon: Activity, perm: 'ATTENDANCE_VIEW' },
  { to: '/app/devices', label: 'Devices', icon: MonitorSmartphone, perm: 'DEVICE_VIEW' },
  { to: '/app/enquiries', label: 'Enquiries', icon: Inbox, perm: 'ENQUIRY_VIEW' },
  { to: '/app/reports', label: 'Reports', icon: BarChart3, perm: 'REPORT_VIEW' },
  { to: '/app/notifications', label: 'Notifications', icon: Bell, perm: 'NOTIFICATION_SEND' },
  { to: '/app/users', label: 'Staff', icon: Users, perm: 'USER_MANAGE' },
  { to: '/app/roles', label: 'Roles', icon: Shield, perm: 'ROLE_MANAGE' },
  { to: '/app/audit', label: 'Audit', icon: ClipboardList, perm: 'AUDIT_VIEW' },
  { to: '/app/settings', label: 'Settings', icon: Settings, perm: 'SETTINGS_MANAGE' },
]

export function AppShell() {
  const { user, logout, has } = useAuth()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const live = useStaffLive()
  const isPlatform = user?.tenantId == null && user?.roles.includes('SUPER_ADMIN')
  const settings = useQuery({
    queryKey: ['settings', 'shell'],
    queryFn: () => api<PublicSite>('/api/v1/settings'),
    enabled: Boolean(user?.tenantId),
  })
  const brand = isPlatform ? 'Platform' : brandDisplayName(settings.data, 'Gym')
  const logo = isPlatform ? null : brandLogo(settings.data)
  const visible = [
    ...(isPlatform
      ? [{ to: '/app/platform/gyms', label: 'Gyms', icon: Building2, perm: null as string | null }]
      : []),
    ...links.filter((l) => {
      if (isPlatform && (l.to === '/app/settings' || l.perm === 'SETTINGS_MANAGE')) return false
      return !l.perm || has(l.perm)
    }),
  ]

  async function onLogout() {
    await logout()
    navigate('/app/login')
  }

  return (
    <div className="min-h-screen bg-canvas text-ink">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-accent focus:px-3 focus:py-2 focus:text-accent-ink"
      >
        Skip to main content
      </a>
      <header className="sticky top-0 z-20 flex items-center justify-between border-b border-line bg-panel/90 px-4 py-3 backdrop-blur md:hidden">
        <span className="flex items-center gap-2 font-extrabold tracking-tight">
          {logo ? <img src={logo} alt="" className="h-7 w-7 rounded-md object-cover" /> : null}
          {brand}
        </span>
        <button
          type="button"
          aria-label="Open menu"
          aria-expanded={open}
          aria-controls="mobile-nav"
          onClick={() => setOpen(true)}
        >
          <Menu className="h-5 w-5" />
        </button>
      </header>

      {open ? (
        <div className="fixed inset-0 z-30 bg-black/60 md:hidden" onClick={() => setOpen(false)}>
          <nav
            id="mobile-nav"
            aria-label="Staff"
            className="h-full w-[min(18rem,88vw)] overflow-y-auto bg-panel p-4 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-6 flex items-center justify-between gap-2">
              <span className="flex items-center gap-2 font-extrabold">
                {logo ? <img src={logo} alt="" className="h-7 w-7 rounded-md object-cover" /> : null}
                {brand}
              </span>
              <button type="button" aria-label="Close menu" onClick={() => setOpen(false)}>
                <X className="h-5 w-5" />
              </button>
            </div>
            <NavItems items={visible} onClick={() => setOpen(false)} />
            <div className="mt-6 border-t border-line pt-4">
              <NavLink
                to="/app/profile"
                onClick={() => setOpen(false)}
                className="block px-3 py-2 text-sm font-medium"
              >
                {user?.fullName}
              </NavLink>
              <Button
                variant="ghost"
                className="mt-3 w-full justify-start"
                onClick={() => {
                  setOpen(false)
                  void onLogout()
                }}
              >
                <LogOut className="h-4 w-4" /> Sign out
              </Button>
            </div>
          </nav>
        </div>
      ) : null}

      <div className="flex min-h-screen w-full">
        <aside
          aria-label="Staff"
          className="sticky top-0 hidden h-screen w-56 shrink-0 flex-col overflow-y-auto border-r border-line bg-panel p-3 md:flex lg:w-60 lg:p-4 xl:w-64"
        >
          <div className="px-2 pb-6 pt-2">
            <div className="flex items-center gap-2">
              {logo ? <img src={logo} alt="" className="h-8 w-8 rounded-md object-cover" /> : null}
              <div className="min-w-0 text-lg font-extrabold tracking-tight leading-tight">{brand}</div>
            </div>
            <div className="mt-1 text-xs text-muted">
              {isPlatform ? 'Super admin' : 'Operations'} · live{' '}
              {live === 'live' ? 'on' : live === 'down' ? 'reconnecting' : 'off'}
            </div>
          </div>
          <NavItems items={visible} />
          <div className="mt-auto border-t border-line pt-4">
            <NavLink to="/app/profile" className="block px-2 text-sm font-medium hover:underline">
              {user?.fullName}
            </NavLink>
            <div className="px-2 text-xs text-muted">{user?.roles.join(', ')}</div>
            <Button variant="ghost" className="mt-3 w-full justify-start" onClick={onLogout}>
              <LogOut className="h-4 w-4" /> Sign out
            </Button>
          </div>
        </aside>
        <main id="main-content" tabIndex={-1} className="min-w-0 flex-1 px-4 py-5 sm:px-6 md:px-8 lg:py-6 xl:px-10">
          <div className="mx-auto w-full max-w-[90rem]">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}

function NavItems({
  items,
  onClick,
}: {
  items: { to: string; label: string; icon: typeof LayoutDashboard; perm: string | null }[]
  onClick?: () => void
}) {
  return (
    <ul className="space-y-1">
      {items.map((item) => (
        <li key={item.to}>
          <NavLink
            to={item.to}
            onClick={onClick}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium text-muted hover:bg-raised hover:text-ink',
                isActive && 'bg-raised text-ink',
              )
            }
          >
            <item.icon className="h-4 w-4" />
            {item.label}
          </NavLink>
        </li>
      ))}
    </ul>
  )
}
