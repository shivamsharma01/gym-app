import { Link, NavLink, Outlet } from 'react-router'
import { cn } from '@/lib/cn'

const links = [
  ['/', 'Home'],
  ['/about', 'About'],
  ['/services', 'Services'],
  ['/facilities', 'Facilities'],
  ['/membership-plans', 'Plans'],
  ['/contact', 'Contact'],
] as const

export function PublicLayout() {
  return (
    <div className="min-h-screen bg-[#0b0c0b] text-[#f4f1ea]">
      <header className="sticky top-0 z-20 border-b border-white/10 bg-[#0b0c0b]/90 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-4">
          <Link to="/" className="text-lg font-extrabold tracking-tight">
            True Gym
          </Link>
          <nav className="hidden flex-wrap items-center gap-5 text-sm font-medium text-white/70 md:flex">
            {links.map(([to, label]) => (
              <NavLink
                key={to}
                to={to}
                end={to === '/'}
                className={({ isActive }) => cn('hover:text-white', isActive && 'text-[#c8f542]')}
              >
                {label}
              </NavLink>
            ))}
          </nav>
          <Link
            to="/app/login"
            className="rounded-full bg-[#c8f542] px-4 py-2 text-sm font-bold text-[#14180f]"
          >
            Staff sign in
          </Link>
        </div>
        <nav className="flex gap-4 overflow-x-auto px-4 pb-3 text-sm text-white/70 md:hidden">
          {links.map(([to, label]) => (
            <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => cn(isActive && 'text-[#c8f542]')}>
              {label}
            </NavLink>
          ))}
        </nav>
      </header>
      <Outlet />
      <footer className="border-t border-white/10 px-4 py-10 text-sm text-white/50">
        <div className="mx-auto flex max-w-6xl flex-col gap-4 sm:flex-row sm:justify-between">
          <div>
            <div className="font-extrabold text-white">True Gym</div>
            <p className="mt-1 max-w-sm">Strength, recovery, and access control for members who train here — not a generic template gym.</p>
          </div>
          <div className="flex flex-col gap-2">
            <Link to="/contact">Enquire</Link>
            <Link to="/membership-plans">Membership plans</Link>
            <Link to="/app/login">Staff</Link>
          </div>
        </div>
      </footer>
    </div>
  )
}
