import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-canvas px-4 text-center">
      <h1 className="text-3xl font-extrabold">Page not found</h1>
      <p className="mt-2 text-sm text-muted">That route is not part of this app yet.</p>
      <Link to="/app/dashboard" className="mt-6 text-sm font-semibold text-accent">
        Go to dashboard
      </Link>
    </div>
  )
}
