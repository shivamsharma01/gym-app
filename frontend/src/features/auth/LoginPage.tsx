import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useNavigate } from 'react-router'
import { z } from 'zod'
import { Button, FieldError, Input, Label } from '@/components/ui'
import { ApiError } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { isPlatformSuperAdmin, platformHomePath } from '@/lib/platform'

const schema = z.object({
  usernameOrEmail: z.string().min(1, 'Required'),
  password: z.string().min(1, 'Required'),
})

type Form = z.infer<typeof schema>

export function LoginPage() {
  const { login, user } = useAuth()
  const navigate = useNavigate()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Form>({ resolver: zodResolver(schema), defaultValues: { usernameOrEmail: '', password: '' } })

  if (user) {
    return <Navigate to={isPlatformSuperAdmin(user) ? platformHomePath() : '/app/dashboard'} replace />
  }

  async function onSubmit(values: Form) {
    setFormError(null)
    try {
      const loggedIn = await login(values.usernameOrEmail, values.password)
      navigate(isPlatformSuperAdmin(loggedIn) ? platformHomePath() : '/app/dashboard', { replace: true })
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : 'Sign in failed')
    }
  }

  return (
    <div className="app-shell-bg flex min-h-screen items-center justify-center px-4 py-10">
      <main className="w-full max-w-md rounded-2xl border border-line bg-panel p-8 shadow-[var(--shadow-panel)]">
        <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-muted">Staff console</p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">Sign in</h1>
        <p className="mt-2 text-sm leading-relaxed text-muted">
          Gym admins and platform operators. Access tokens stay in memory — closing the tab signs you out.
        </p>
        <form className="mt-8 space-y-4" onSubmit={form.handleSubmit(onSubmit)}>
          <div>
            <Label htmlFor="usernameOrEmail">Username or email</Label>
            <Input id="usernameOrEmail" autoComplete="username" {...form.register('usernameOrEmail')} />
            <FieldError message={form.formState.errors.usernameOrEmail?.message} />
          </div>
          <div>
            <Label htmlFor="password">Password</Label>
            <Input id="password" type="password" autoComplete="current-password" {...form.register('password')} />
            <FieldError message={form.formState.errors.password?.message} />
          </div>
          {formError ? (
            <p className="rounded-lg border border-danger/25 bg-danger/8 px-3 py-2 text-sm text-danger" role="alert">
              {formError}
            </p>
          ) : null}
          <Button className="w-full" type="submit" disabled={form.formState.isSubmitting}>
            {form.formState.isSubmitting ? 'Signing in…' : 'Continue'}
          </Button>
        </form>
        <p className="mt-6 text-center text-sm text-muted">
          <Link to="/app/forgot-password" className="underline-offset-4 hover:text-ink hover:underline">
            Forgot password
          </Link>
        </p>
      </main>
    </div>
  )
}
