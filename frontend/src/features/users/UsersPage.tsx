import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { Badge, Button, Input, Label, PageHeader, Select, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { STAFF_ROLES } from '@/lib/catalog'
import { statusTone } from '@/lib/status'
import type { PageResponse } from '@/lib/types'

type StaffUser = {
  id: string
  username: string
  email: string
  fullName: string
  status: string
  roles: string[]
}

type RoleOption = { id: string; name: string; description: string | null }

function roleLabel(name: string) {
  return STAFF_ROLES.find((r) => r.name === name)?.label ?? name
}

export function UsersPage() {
  const qc = useQueryClient()
  const users = useQuery({
    queryKey: ['users'],
    queryFn: () => api<PageResponse<StaffUser>>('/api/v1/users?size=50'),
  })
  const catalog = useQuery({
    queryKey: ['roles'],
    queryFn: () => api<RoleOption[]>('/api/v1/roles'),
  })
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [fullName, setFullName] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState('STAFF')
  const available = new Set((catalog.data ?? []).map((r) => r.name))
  const assignable = STAFF_ROLES.filter((r) => available.has(r.name))
  const selected = STAFF_ROLES.find((r) => r.name === role)
  const create = useMutation({
    mutationFn: () =>
      api('/api/v1/users', {
        method: 'POST',
        body: JSON.stringify({
          username,
          email,
          fullName,
          password,
          roles: [role],
        }),
      }),
    onSuccess: () => {
      setUsername('')
      setEmail('')
      setFullName('')
      setPassword('')
      void qc.invalidateQueries({ queryKey: ['users'] })
    },
  })
  const disable = useMutation({
    mutationFn: (id: string) => api(`/api/v1/users/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['users'] }),
  })

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(18rem,24rem)]">
      <div>
        <PageHeader
          title="Staff accounts"
          description="Logins for this app only. Gym members are created under Members. The TrueFace tablet never sees these roles."
        />
        {users.isLoading ? <Skeleton className="h-32" /> : null}
        {users.error ? <QueryError error={users.error} /> : null}
        <ul className="divide-y divide-line rounded-xl border border-line">
          {users.data?.content.map((u) => (
            <li key={u.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-sm">
              <div>
                <div className="font-semibold">{u.fullName}</div>
                <div className="text-muted">
                  {u.username} · {u.roles.map(roleLabel).join(', ')}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <Badge tone={statusTone(u.status)}>{u.status}</Badge>
                {u.status === 'ACTIVE' ? (
                  <Button variant="danger" onClick={() => disable.mutate(u.id)}>
                    Disable
                  </Button>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      </div>
      <form
        className="space-y-3"
        onSubmit={(e) => {
          e.preventDefault()
          create.mutate()
        }}
      >
        <h2 className="text-sm font-semibold uppercase text-muted">New staff login</h2>
        <p className="text-sm text-muted">
          To enrol someone on the door, create them as a{' '}
          <Link className="underline" to="/app/members/new">
            member
          </Link>
          , then map them on the member page.
        </p>
        <Label>Username</Label>
        <Input value={username} onChange={(e) => setUsername(e.target.value)} />
        <Label>Email</Label>
        <Input value={email} onChange={(e) => setEmail(e.target.value)} />
        <Label>Full name</Label>
        <Input value={fullName} onChange={(e) => setFullName(e.target.value)} />
        <Label htmlFor="new-user-password">Password</Label>
        <Input id="new-user-password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        <Label htmlFor="new-user-role">App role</Label>
        <Select id="new-user-role" value={role} onChange={(e) => setRole(e.target.value)} disabled={catalog.isLoading}>
          {assignable.length === 0 ? <option value={role}>{roleLabel(role)}</option> : null}
          {assignable.map((r) => (
            <option key={r.name} value={r.name}>
              {r.label}
            </option>
          ))}
        </Select>
        {selected ? <p className="text-xs text-muted">{selected.description}</p> : null}
        {catalog.error ? <QueryError error={catalog.error} /> : null}
        {create.error ? <QueryError error={create.error} /> : null}
        <Button type="submit" disabled={create.isPending || !role}>
          Create
        </Button>
      </form>
    </div>
  )
}
