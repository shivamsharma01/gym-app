import { NavLink, Outlet, useNavigate } from 'react-router'
import {
  Activity,
  BarChart3,
  Bell,
  Building2,
  ClipboardList,
  CreditCard,
  IdCard,
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
import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button, LiveDot } from '@/components/ui'
import { useAuth } from '@/lib/auth'
import { brandDisplayName, brandLogo, type PublicSite } from '@/lib/brand'
import { api } from '@/lib/api'
import { cn } from '@/lib/cn'
import { useStaffLive } from '@/lib/live'

type NavItem = { to: string; label: string; icon: typeof LayoutDashboard; perm: string | null }

const operations: NavItem[] = [
  { to: '/app/dashboard', label: 'Dashboard', icon: LayoutDashboard, perm: null },
  { to: '/app/members', label: 'Members', icon: Users, perm: 'MEMBER_VIEW' },
  { to: '/app/memberships', label: 'Memberships', icon: IdCard, perm: 'MEMBERSHIP_VIEW' },
  { to: '/app/plans', label: 'Plans', icon: CreditCard, perm: 'MEMBERSHIP_VIEW' },
  { to: '/app/payments', label: 'Payments', icon: Wallet, perm: 'PAYMENT_VIEW' },
  { to: '/app/attendance', label: 'Attendance', icon: Activity, perm: 'ATTENDANCE_VIEW' },
]

const facility: NavItem[] = [
  { to: '/app/devices', label: 'Devices', icon: MonitorSmartphone, perm: 'DEVICE_VIEW' },
  { to: '/app/enquiries', label: 'Enquiries', icon: Inbox, perm: 'ENQUIRY_VIEW' },
  { to: '/app/notifications', label: 'Notifications', icon: Bell, perm: 'NOTIFICATION_SEND' },
  { to: '/app/reports', label: 'Reports', icon: BarChart3, perm: 'REPORT_VIEW' },
]

const admin: NavItem[] = [
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

  const groups = useMemo(() => {
    const filter = (items: NavItem[]) =>
      items.filter((l) => {
        if (isPlatform && (l.to === '/app/settings' || l.perm === 'SETTINGS_MANAGE')) return false
        return !l.perm || has(l.perm)
      })

    const result: { label: string; items: NavItem[] }[] = []
    if (isPlatform) {
      result.push({
        label: 'Platform',
        items: [{ to: '/app/platform/gyms', label: 'Gyms', icon: Building2, perm: null }],
      })
    }
    const ops = filter(operations)
    const fac = filter(facility)
    const adm = filter(admin)
    if (ops.length) result.push({ label: 'Operations', items: ops })
    if (fac.length) result.push({ label: 'Facility', items: fac })
    if (adm.length) result.push({ label: 'Admin', items: adm })
    return result
  }, [has, isPlatform])

  async function onLogout() {
    await logout()
    navigate('/app/login')
  }

  return (
    <div className="app-shell-bg min-h-screen text-ink">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-lg focus:bg-accent focus:px-3 focus:py-2 focus:text-accent-ink"
      >
        Skip to main content
      </a>

      <header className="sticky top-0 z-20 flex items-center justify-between border-b border-line bg-panel/90 px-4 py-3 backdrop-blur-md md:hidden">
        <span className="flex min-w-0 items-center gap-2.5 font-bold tracking-tight">
          {logo ? <img src={logo} alt="" className="h-7 w-7 rounded-lg object-cover" /> : null}
          <span className="truncate">{brand}</span>
        </span>
        <button
          type="button"
          className="rounded-lg p-2 text-muted hover:bg-raised hover:text-ink"
          aria-label="Open menu"
          aria-expanded={open}
          aria-controls="mobile-nav"
          onClick={() => setOpen(true)}
        >
          <Menu className="h-5 w-5" />
        </button>
      </header>

      {open ? (
        <div className="fixed inset-0 z-30 bg-black/60 animate-[overlay-in_0.16s_ease-out] md:hidden" onClick={() => setOpen(false)}>
          <nav
            id="mobile-nav"
            aria-label="Staff"
            className="flex h-full w-[min(19rem,90vw)] flex-col overflow-y-auto border-r border-line bg-panel p-4 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-5 flex items-center justify-between gap-2">
              <span className="flex min-w-0 items-center gap-2.5 font-bold">
                {logo ? <img src={logo} alt="" className="h-7 w-7 rounded-lg object-cover" /> : null}
                <span className="truncate">{brand}</span>
              </span>
              <button type="button" className="rounded-lg p-2 text-muted hover:bg-raised" aria-label="Close menu" onClick={() => setOpen(false)}>
                <X className="h-5 w-5" />
              </button>
            </div>
            <NavGroups groups={groups} onClick={() => setOpen(false)} />
            <UserFooter
              name={user?.fullName}
              roles={user?.roles}
              onLogout={() => {
                setOpen(false)
                void onLogout()
              }}
              onProfile={() => setOpen(false)}
            />
          </nav>
        </div>
      ) : null}

      <div className="flex min-h-screen w-full">
        <aside
          aria-label="Staff"
          className="sticky top-0 hidden h-screen w-60 shrink-0 flex-col overflow-y-auto border-r border-line bg-panel/95 p-3 lg:w-64 lg:p-4 md:flex"
        >
          <div className="px-2 pb-5 pt-2">
            <div className="flex items-center gap-2.5">
              {logo ? <img src={logo} alt="" className="h-8 w-8 rounded-lg object-cover ring-1 ring-line" /> : (
                <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-accent-soft text-xs font-bold text-accent">
                  {brand.slice(0, 1).toUpperCase()}
                </span>
              )}
              <div className="min-w-0">
                <div className="truncate text-[15px] font-bold tracking-tight leading-tight">{brand}</div>
                <div className="mt-0.5 flex items-center gap-2 text-[11px] text-muted">
                  <span>{isPlatform ? 'Super admin' : 'Staff console'}</span>
                  <span aria-hidden>·</span>
                  <LiveDot state={live} />
                </div>
              </div>
            </div>
          </div>
          <NavGroups groups={groups} />
          <UserFooter name={user?.fullName} roles={user?.roles} onLogout={() => void onLogout()} />
        </aside>

        <div className="flex min-w-0 flex-1 flex-col">
          <div className="sticky top-0 z-10 hidden items-center justify-between border-b border-line/80 bg-canvas/80 px-8 py-3 backdrop-blur-md md:flex xl:px-10">
            <p className="text-sm text-muted">
              Signed in as <span className="font-medium text-ink">{user?.fullName}</span>
            </p>
            <LiveDot state={live} />
          </div>
          <main id="main-content" tabIndex={-1} className="min-w-0 flex-1 px-4 py-5 sm:px-6 md:px-8 lg:py-7 xl:px-10">
            <div className="mx-auto w-full max-w-[90rem] animate-[fade-up_0.45s_ease-out]">
              <Outlet />
            </div>
          </main>
        </div>
      </div>
    </div>
  )
}

function NavGroups({
  groups,
  onClick,
}: {
  groups: { label: string; items: NavItem[] }[]
  onClick?: () => void
}) {
  return (
    <div className="flex flex-1 flex-col gap-5">
      {groups.map((group) => (
        <div key={group.label}>
          <p className="mb-1.5 px-3 text-[10px] font-semibold uppercase tracking-[0.14em] text-muted/80">{group.label}</p>
          <ul className="space-y-0.5">
            {group.items.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  onClick={onClick}
                  className={({ isActive }) =>
                    cn(
                      'group flex items-center gap-2.5 rounded-lg px-3 py-2 text-[13px] font-medium text-muted transition duration-150 hover:bg-raised hover:text-ink',
                      isActive && 'bg-raised text-ink shadow-[inset_2px_0_0_0_var(--color-accent)]',
                    )
                  }
                >
                  <item.icon className="h-4 w-4 shrink-0 opacity-80" aria-hidden />
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}

function UserFooter({
  name,
  roles,
  onLogout,
  onProfile,
}: {
  name?: string
  roles?: string[]
  onLogout: () => void
  onProfile?: () => void
}) {
  return (
    <div className="mt-auto border-t border-line pt-4">
      <NavLink
        to="/app/profile"
        onClick={onProfile}
        className="block rounded-lg px-2 py-1.5 transition hover:bg-raised"
      >
        <div className="truncate text-sm font-medium">{name}</div>
        <div className="truncate text-[11px] text-muted">{roles?.join(' · ')}</div>
      </NavLink>
      <Button variant="ghost" size="sm" className="mt-2 w-full justify-start" onClick={onLogout}>
        <LogOut className="h-4 w-4" /> Sign out
      </Button>
    </div>
  )
}
