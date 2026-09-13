import { Link, NavLink, Outlet } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { brandDisplayName, brandLogo, gymPath, type PublicSite } from '@/lib/brand'
import { useGymSlug } from '@/lib/GymSlug'
import { publicApi } from '@/lib/publicApi'
import { cn } from '@/lib/cn'

const linkDefs = [
  ['', 'Home'],
  ['/about', 'About'],
  ['/services', 'Services'],
  ['/facilities', 'Facilities'],
  ['/membership-plans', 'Plans'],
  ['/contact', 'Contact'],
] as const

export function PublicLayout() {
  const slug = useGymSlug()
  const site = useQuery({
    queryKey: ['public-site', slug],
    queryFn: () => publicApi<PublicSite>(slug, '/api/v1/public/site'),
  })
  const name = brandDisplayName(site.data)
  const logo = brandLogo(site.data)

  return (
    <div className="min-h-screen bg-[#0b0c0b] text-[#f4f1ea]">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-[#c8f542] focus:px-3 focus:py-2 focus:text-[#14180f]"
      >
        Skip to main content
      </a>
      <header className="sticky top-0 z-20 border-b border-white/10 bg-[#0b0c0b]/90 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-4">
          <Link to={gymPath(slug)} className="flex min-w-0 items-center gap-3">
            <img src={logo} alt="" className="h-9 w-9 shrink-0 rounded-lg object-cover" />
            <span className="truncate text-lg font-extrabold tracking-tight md:text-xl">{name}</span>
          </Link>
          <nav aria-label="Primary" className="hidden flex-wrap items-center gap-5 text-sm font-medium text-white/70 md:flex">
            {linkDefs.map(([rest, label]) => (
              <NavLink
                key={rest || 'home'}
                to={gymPath(slug, rest)}
                end={rest === ''}
                className={({ isActive }) => cn('hover:text-white transition-colors', isActive && 'text-[#c8f542]')}
              >
                {label}
              </NavLink>
            ))}
          </nav>
          <Link
            to="/app/login"
            className="shrink-0 rounded-full bg-[#c8f542] px-4 py-2 text-sm font-bold text-[#14180f] transition hover:brightness-110"
          >
            Staff sign in
          </Link>
        </div>
        <nav aria-label="Mobile" className="flex gap-4 overflow-x-auto px-4 pb-3 text-sm text-white/70 md:hidden">
          {linkDefs.map(([rest, label]) => (
            <NavLink
              key={rest || 'home-m'}
              to={gymPath(slug, rest)}
              end={rest === ''}
              className={({ isActive }) => cn('whitespace-nowrap', isActive && 'text-[#c8f542]')}
            >
              {label}
            </NavLink>
          ))}
        </nav>
      </header>
      <main id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="border-t border-white/10 px-4 py-10 text-sm text-white/50">
        <div className="mx-auto flex max-w-6xl flex-col gap-4 sm:flex-row sm:justify-between">
          <div>
            <div className="flex items-center gap-2 font-extrabold text-white">
              <img src={logo} alt="" className="h-7 w-7 rounded-md object-cover" />
              {name}
            </div>
            <p className="mt-1 max-w-sm">
              {site.data?.tagline || 'Strength, recovery, and access control for members who train here.'}
            </p>
          </div>
          <div className="flex flex-col gap-2">
            <Link to={gymPath(slug, '/contact')}>Enquire</Link>
            <Link to={gymPath(slug, '/membership-plans')}>Membership plans</Link>
            <Link to="/app/login">Staff</Link>
          </div>
        </div>
      </footer>
    </div>
  )
}
