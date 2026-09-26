import { Navigate, Outlet } from 'react-router'
import { useAuth } from '@/lib/auth'

export function RequireAuth() {
  const { user, ready } = useAuth()
  if (!ready) {
    return (
      <div className="app-shell-bg flex min-h-screen items-center justify-center text-sm text-muted">
        Restoring session…
      </div>
    )
  }
  if (!user) return <Navigate to="/app/login" replace />
  return <Outlet />
}
