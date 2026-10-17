import { Navigate } from 'react-router'
import { useAuth } from '@/lib/useAuth'
import { isPlatformSuperAdmin, platformHomePath } from '@/lib/platform'

export function AppIndexRedirect() {
  const { user } = useAuth()
  if (isPlatformSuperAdmin(user)) {
    return <Navigate to={platformHomePath()} replace />
  }
  return <Navigate to="dashboard" replace />
}
