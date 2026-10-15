import type { ReactNode } from 'react'
import { Navigate, Outlet } from 'react-router'
import { useAuth } from '@/lib/auth'
import { isPlatformSuperAdmin, platformHomePath } from '@/lib/platform'

/** Gym staff routes — redirect platform SUPER_ADMIN away from tenant UI. */
export function RequireGymTenant() {
  const { user } = useAuth()
  if (isPlatformSuperAdmin(user)) {
    return <Navigate to={platformHomePath()} replace />
  }
  return <Outlet />
}

/** Pages the signed-in user may open only with one of these permissions; others go to the dashboard. */
export function RequirePermission({ anyOf, children }: Readonly<{ anyOf: string[]; children: ReactNode }>) {
  const { user, has } = useAuth()
  if (isPlatformSuperAdmin(user) || anyOf.some((permission) => has(permission))) {
    return <>{children}</>
  }
  return <Navigate to="/app/dashboard" replace />
}

/** Platform-only routes. */
export function RequirePlatform() {
  const { user } = useAuth()
  if (!isPlatformSuperAdmin(user)) {
    return <Navigate to="/app/dashboard" replace />
  }
  return <Outlet />
}
