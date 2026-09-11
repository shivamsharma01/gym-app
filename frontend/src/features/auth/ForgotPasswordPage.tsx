import { Link } from 'react-router'

export function ForgotPasswordPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-canvas px-4">
      <div className="w-full max-w-md rounded-2xl border border-line bg-panel p-8">
        <h1 className="text-2xl font-extrabold">Reset password</h1>
        <p className="mt-3 text-sm text-muted">
          Self-service reset is not available yet. Ask a gym admin to update your account.
        </p>
        <Link to="/app/login" className="mt-6 inline-block text-sm font-semibold text-accent">
          Back to sign in
        </Link>
      </div>
    </div>
  )
}
