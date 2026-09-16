import { Link } from 'react-router'
import { Button } from '@/components/ui'

export function NotFoundPage() {
  return (
    <div className="app-shell-bg flex min-h-screen flex-col items-center justify-center px-4 text-center">
      <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-muted">404</p>
      <h1 className="mt-2 text-3xl font-bold tracking-tight">Page not found</h1>
      <p className="mt-2 max-w-sm text-sm text-muted">That route is not part of this app.</p>
      <Link to="/app/dashboard" className="mt-6">
        <Button>Go to dashboard</Button>
      </Link>
    </div>
  )
}
