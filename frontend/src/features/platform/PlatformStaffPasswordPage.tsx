import { useMutation, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { QueryError } from '@/components/QueryError'
import { Button, Card, FieldError, Input, Label, PageHeader, Select, Skeleton } from '@/components/ui'
import { api } from '@/lib/api'

type TenantSummary = {
  id: string
  name: string
  slug: string
  status: string
  displayName: string
}

type StaffUser = {
  id: string
  username: string
  email: string
  fullName: string
  status: string
  roles: string[]
}

export function PlatformStaffPasswordPage() {
  const gyms = useQuery({
    queryKey: ['platform-tenants'],
    queryFn: () => api<TenantSummary[]>('/api/v1/platform/tenants'),
  })
  const [tenantId, setTenantId] = useState('')
  const [username, setUsername] = useState('')
  const staff = useQuery({
    queryKey: ['platform-tenant-users', tenantId],
    queryFn: () => api<StaffUser[]>(`/api/v1/platform/tenants/${tenantId}/users`),
    enabled: Boolean(tenantId),
  })
  const selected = useMemo(
    () => staff.data?.find((u) => u.username === username) ?? null,
    [staff.data, username],
  )
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const setPasswordMut = useMutation({
    mutationFn: () =>
      api(`/api/v1/platform/tenants/${tenantId}/users/${encodeURIComponent(username)}/password`, {
        method: 'PUT',
        body: JSON.stringify({ newPassword: password }),
      }),
    onSuccess: () => {
      setPassword('')
      setConfirm('')
      setDone(true)
    },
  })

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <PageHeader
        title="Staff passwords"
        description="Set a gym login without the old password. Give the new password to that person in person — they should change it on Profile."
      />

      <Card className="space-y-4">
        <div>
          <Label htmlFor="tenant">Gym</Label>
          {gyms.isLoading ? <Skeleton className="mt-1 h-10" /> : null}
          {gyms.error ? <QueryError error={gyms.error} onRetry={() => void gyms.refetch()} /> : null}
          <Select
            id="tenant"
            value={tenantId}
            onChange={(e) => {
              setTenantId(e.target.value)
              setUsername('')
              setDone(false)
            }}
          >
            <option value="">Select a gym</option>
            {(gyms.data ?? []).map((g) => (
              <option key={g.id} value={g.id}>
                {g.displayName} ({g.slug})
              </option>
            ))}
          </Select>
        </div>
        <div>
          <Label htmlFor="staff-user">Username</Label>
          {!tenantId ? <p className="mt-1 text-sm text-muted">Choose a gym first.</p> : null}
          {tenantId && staff.isLoading ? <Skeleton className="mt-1 h-10" /> : null}
          {staff.error ? <QueryError error={staff.error} onRetry={() => void staff.refetch()} /> : null}
          {tenantId && staff.data ? (
            <Select
              id="staff-user"
              value={username}
              onChange={(e) => {
                setUsername(e.target.value)
                setDone(false)
              }}
            >
              <option value="">Select a username</option>
              {staff.data.map((u) => (
                <option key={u.id} value={u.username}>
                  {u.username}
                </option>
              ))}
            </Select>
          ) : null}
        </div>
      </Card>

      {selected ? (
        <Card className="space-y-5">
          <div>
            <h2 className="text-base font-semibold tracking-tight">Profile</h2>
            <p className="mt-1 text-sm text-muted">This person cannot use email reset. Hand them the new password.</p>
          </div>
          <ProfileRow label="Name" value={selected.fullName} />
          <ProfileRow label="Username" value={selected.username} />
          <ProfileRow label="Email" value={selected.email} />
          <ProfileRow label="Status" value={selected.status} />
          <ProfileRow label="Roles" value={selected.roles.join(', ') || '—'} />

          <form
            className="space-y-3 border-t border-line pt-4"
            onSubmit={(e) => {
              e.preventDefault()
              setFormError(null)
              setDone(false)
              if (password.length < 10) {
                setFormError('Password must be at least 10 characters.')
                return
              }
              if (password !== confirm) {
                setFormError('New password and confirmation do not match.')
                return
              }
              setPasswordMut.mutate()
            }}
          >
            <h3 className="text-sm font-semibold tracking-tight">Set new password</h3>
            <div>
              <Label htmlFor="new-password">New password</Label>
              <Input
                id="new-password"
                type="password"
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                minLength={10}
                required
              />
            </div>
            <div>
              <Label htmlFor="confirm-password">Confirm new password</Label>
              <Input
                id="confirm-password"
                type="password"
                autoComplete="new-password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                minLength={10}
                required
              />
            </div>
            {formError ? <FieldError message={formError} /> : null}
            {setPasswordMut.error ? <QueryError error={setPasswordMut.error} /> : null}
            {done ? (
              <p className="text-sm text-ok" role="status">
                Password updated. Tell them to sign in and change it under Profile.
              </p>
            ) : null}
            <Button type="submit" disabled={setPasswordMut.isPending}>
              {setPasswordMut.isPending ? 'Saving…' : 'Set password'}
            </Button>
          </form>
        </Card>
      ) : null}
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
