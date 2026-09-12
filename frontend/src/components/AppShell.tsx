import { NavLink, Outlet, useNavigate } from 'react-router'
import {
  Activity,
  BarChart3,
  Bell,
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
import { Button } from '@/components/ui'
import { useAuth } from '@/lib/auth'
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
  const visible = links.filter((l) => !l.perm || has(l.perm))

  async function onLogout() {
    await logout()
    navigate('/app/login')
  }

  return (
    <div className="min-h-screen bg-canvas text-ink">
      <header className="sticky top-0 z-20 flex items-center justify-between border-b border-line bg-panel/90 px-4 py-3 backdrop-blur md:hidden">
        <span className="font-extrabold tracking-tight">True Gym</span>
        <button type="button" aria-label="Open menu" onClick={() => setOpen(true)}>
          <Menu className="h-5 w-5" />
        </button>
      </header>

      {open ? (
        <div className="fixed inset-0 z-30 bg-black/60 md:hidden" onClick={() => setOpen(false)}>
          <nav
            className="h-full w-[min(18rem,88vw)] overflow-y-auto bg-panel p-4 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-6 flex items-center justify-between">
              <span className="font-extrabold">True Gym</span>
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

      {/* Full-bleed shell: sidebar flush to the viewport edge (no centered max-width gutter). */}
      <div className="flex min-h-screen w-full">
        <aside className="sticky top-0 hidden h-screen w-56 shrink-0 flex-col overflow-y-auto border-r border-line bg-panel p-3 md:flex lg:w-60 lg:p-4 xl:w-64">
          <div className="px-2 pb-6 pt-2">
            <div className="text-lg font-extrabold tracking-tight">True Gym</div>
            <div className="text-xs text-muted">
              Operations · live {live === 'live' ? 'on' : live === 'down' ? 'reconnecting' : 'off'}
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
        <main className="min-w-0 flex-1 px-4 py-5 sm:px-6 md:px-8 lg:py-6 xl:px-10">
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
  items: typeof links
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
