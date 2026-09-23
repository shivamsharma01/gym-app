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

/** Platform-only routes. */
export function RequirePlatform() {
  const { user } = useAuth()
  if (!isPlatformSuperAdmin(user)) {
    return <Navigate to="/app/dashboard" replace />
  }
  return <Outlet />
}
