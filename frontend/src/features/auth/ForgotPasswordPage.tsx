import { Link } from 'react-router'
import { Button } from '@/components/ui'

export function ForgotPasswordPage() {
  return (
    <div className="app-shell-bg flex min-h-screen items-center justify-center px-4 py-10">
      <div className="w-full max-w-md rounded-2xl border border-line bg-panel p-8 shadow-[var(--shadow-panel)]">
        <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-muted">Account</p>
        <h1 className="mt-2 text-2xl font-bold tracking-tight">Reset password</h1>
        <p className="mt-3 text-sm leading-relaxed text-muted">
          Self-service email reset is not available. Contact a Super Admin to set a new password for
          your account and share it with you securely. After you sign in, change it under Profile.
        </p>
        <Link to="/app/login" className="mt-6 inline-block">
          <Button variant="outline">Back to sign in</Button>
        </Link>
      </div>
    </div>
  )
}
