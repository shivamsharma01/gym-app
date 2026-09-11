import { Navigate, Outlet } from 'react-router'
import { useAuth } from '@/lib/auth'

export function RequireAuth() {
  const { user } = useAuth()
  if (!user) return <Navigate to="/app/login" replace />
  return <Outlet />
}
