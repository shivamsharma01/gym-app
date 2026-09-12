import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Badge, Button, Input, Label, PageHeader, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
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

export function UsersPage() {
  const qc = useQueryClient()
  const users = useQuery({
    queryKey: ['users'],
    queryFn: () => api<PageResponse<StaffUser>>('/api/v1/users?size=50'),
  })
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [fullName, setFullName] = useState('')
  const [password, setPassword] = useState('')
  const [roles, setRoles] = useState('STAFF')
  const create = useMutation({
    mutationFn: () =>
      api('/api/v1/users', {
        method: 'POST',
        body: JSON.stringify({
          username,
          email,
          fullName,
          password,
          roles: roles.split(',').map((r) => r.trim()),
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
    <div className="grid gap-8 lg:grid-cols-[1fr_20rem]">
      <div>
        <PageHeader title="Users" />
        {users.isLoading ? <Skeleton className="h-32" /> : null}
        {users.error ? <QueryError error={users.error} /> : null}
        <ul className="divide-y divide-line rounded-xl border border-line">
          {users.data?.content.map((u) => (
            <li key={u.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-sm">
              <div>
                <div className="font-semibold">{u.fullName}</div>
                <div className="text-muted">
                  {u.username} · {u.roles.join(', ')}
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
        <h2 className="text-sm font-semibold uppercase text-muted">New user</h2>
        <Label>Username</Label>
        <Input value={username} onChange={(e) => setUsername(e.target.value)} />
        <Label>Email</Label>
        <Input value={email} onChange={(e) => setEmail(e.target.value)} />
        <Label>Full name</Label>
        <Input value={fullName} onChange={(e) => setFullName(e.target.value)} />
        <Label>Password</Label>
        <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        <Label>Roles (comma)</Label>
        <Input value={roles} onChange={(e) => setRoles(e.target.value)} />
        {create.error ? <QueryError error={create.error} /> : null}
        <Button type="submit">Create</Button>
      </form>
    </div>
  )
}
