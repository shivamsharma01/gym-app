import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { z } from 'zod'
import { QueryError } from '@/components/QueryError'
import { Badge, Button, Card, FieldError, Input, Label, PageHeader, Skeleton } from '@/components/ui'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import type { UserSummary } from '@/lib/types'

const schema = z
  .object({
    currentPassword: z.string().min(1, 'Required'),
    newPassword: z.string().min(10, 'At least 10 characters'),
    confirmPassword: z.string().min(1, 'Required'),
  })
  .refine((v) => v.newPassword === v.confirmPassword, {
    path: ['confirmPassword'],
    message: 'Passwords do not match',
  })
  .refine((v) => v.currentPassword !== v.newPassword, {
    path: ['newPassword'],
    message: 'New password must be different from the current password',
  })

type Form = z.infer<typeof schema>

export function ProfilePage() {
  const navigate = useNavigate()
  const { logout } = useAuth()
  const me = useQuery({
    queryKey: ['me'],
    queryFn: () => api<UserSummary>('/api/v1/me'),
  })
  const form = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  })
  const change = useMutation({
    mutationFn: (body: Form) =>
      api('/api/v1/me/password', {
        method: 'POST',
        body: JSON.stringify({
          currentPassword: body.currentPassword,
          newPassword: body.newPassword,
        }),
      }),
    onSuccess: async () => {
      await logout()
      navigate('/app/login', { replace: true })
    },
  })

  return (
    <div className="mx-auto max-w-xl space-y-8">
      <PageHeader
        title="Profile"
        description="Your staff account. After a platform reset, change the temporary password here."
      />
      {me.isLoading ? <Skeleton className="h-32" /> : null}
      {me.error ? <QueryError error={me.error} onRetry={() => void me.refetch()} /> : null}
      {me.data ? (
        <Card className="space-y-5">
          <ProfileRow label="Name" value={me.data.fullName} />
          <ProfileRow label="Username" value={me.data.username} />
          <ProfileRow label="Email" value={me.data.email} />
          <div>
            <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">Roles</div>
            <div className="mt-2 flex flex-wrap gap-1.5">
              {me.data.roles.map((r) => (
                <Badge key={r} tone="accent">
                  {r}
                </Badge>
              ))}
            </div>
          </div>
          <div>
            <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">Permissions</div>
            <p className="mt-2 text-sm leading-relaxed text-muted">{me.data.permissions.join(' · ')}</p>
          </div>
        </Card>
      ) : null}

      <Card>
        <h2 className="text-base font-semibold tracking-tight">Change password</h2>
        <p className="mt-1 text-sm leading-relaxed text-muted">
          Enter your current password, then the new one twice. There is no email reset.
        </p>
        <form className="mt-5 space-y-4" onSubmit={form.handleSubmit((v) => change.mutate(v))}>
          <div>
            <Label htmlFor="currentPassword">Current password</Label>
            <Input
              id="currentPassword"
              type="password"
              autoComplete="current-password"
              {...form.register('currentPassword')}
            />
            <FieldError message={form.formState.errors.currentPassword?.message} />
          </div>
          <div>
            <Label htmlFor="newPassword">New password</Label>
            <Input id="newPassword" type="password" autoComplete="new-password" {...form.register('newPassword')} />
            <FieldError message={form.formState.errors.newPassword?.message} />
          </div>
          <div>
            <Label htmlFor="confirmPassword">Confirm new password</Label>
            <Input
              id="confirmPassword"
              type="password"
              autoComplete="new-password"
              {...form.register('confirmPassword')}
            />
            <FieldError message={form.formState.errors.confirmPassword?.message} />
          </div>
          {change.error ? <QueryError error={change.error} /> : null}
          <Button type="submit" disabled={change.isPending}>
            {change.isPending ? 'Saving…' : 'Update password'}
          </Button>
        </form>
      </Card>
    </div>
  )
}

function ProfileRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">{label}</div>
      <div className="mt-1 text-sm font-medium">{value}</div>
    </div>
  )
}
