import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { Button, Input, Label, PageHeader, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { apiUrl } from '@/lib/backendUrls'
import { gymPath } from '@/lib/brand'
import { getAccessToken } from '@/lib/tokens'

type TenantSummary = {
  id: string
  name: string
  slug: string
  status: string
  displayName: string
}

type EnrollResponse = {
  tenant: TenantSummary
  ownerUsername: string
  ownerEmail: string
}

export function PlatformGymsPage() {
  const qc = useQueryClient()
  const gyms = useQuery({
    queryKey: ['platform-tenants'],
    queryFn: () => api<TenantSummary[]>('/api/v1/platform/tenants'),
  })
  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [ownerUsername, setOwnerUsername] = useState('')
  const [ownerEmail, setOwnerEmail] = useState('')
  const [ownerFullName, setOwnerFullName] = useState('')
  const [ownerPassword, setOwnerPassword] = useState('')
  const [created, setCreated] = useState<EnrollResponse | null>(null)

  const enroll = useMutation({
    mutationFn: () =>
      api<EnrollResponse>('/api/v1/platform/tenants', {
        method: 'POST',
        body: JSON.stringify({
          name,
          slug: slug.toLowerCase().trim(),
          displayName: displayName || name,
          ownerUsername,
          ownerEmail,
          ownerFullName,
          ownerPassword,
        }),
      }),
    onSuccess: (res) => {
      setCreated(res)
      setName('')
      setSlug('')
      setDisplayName('')
      setOwnerUsername('')
      setOwnerEmail('')
      setOwnerFullName('')
      setOwnerPassword('')
      void qc.invalidateQueries({ queryKey: ['platform-tenants'] })
    },
  })

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(18rem,24rem)]">
      <div>
        <PageHeader
          title="Enrolled gyms"
          description="Platform SUPER_ADMIN only. Each gym gets its own admins, members, and public site at /g/{slug}."
        />
        {gyms.isLoading ? <Skeleton className="h-32" /> : null}
        {gyms.error ? <QueryError error={gyms.error} /> : null}
        <ul className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-panel shadow-[var(--shadow-panel)]">
          {(gyms.data ?? []).map((g) => (
            <li key={g.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-4 text-sm">
              <div>
                <div className="text-base font-bold tracking-tight">{g.displayName}</div>
                <div className="mt-0.5 text-muted">
                  {g.name} · <code className="text-xs">{g.slug}</code> · {g.status}
                </div>
              </div>
              <div className="flex flex-wrap gap-3">
                <button
                  type="button"
                  className="font-semibold text-accent hover:underline"
                  onClick={() => {
                    void (async () => {
                      const res = await fetch(apiUrl(`/api/v1/platform/tenants/${g.id}/members/export.csv`), {
                        headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
                      })
                      if (!res.ok) return
                      const blob = await res.blob()
                      const url = URL.createObjectURL(blob)
                      const a = document.createElement('a')
                      a.href = url
                      a.download = `members-${g.slug}.csv`
                      a.click()
                      URL.revokeObjectURL(url)
                    })()
                  }}
                >
                  Export members CSV
                </button>
                <Link to={gymPath(g.slug)} className="font-semibold text-accent hover:underline">
                  Public site
                </Link>
              </div>
            </li>
          ))}
        </ul>
        {gyms.data?.length === 0 ? <p className="mt-4 text-sm text-muted">No gyms yet. Enroll the first one.</p> : null}
      </div>

      <form
        className="space-y-3 rounded-2xl border border-line bg-panel p-5 shadow-[var(--shadow-panel)]"
        onSubmit={(e) => {
          e.preventDefault()
          enroll.mutate()
        }}
      >
        <h2 className="text-sm font-semibold tracking-tight">Enroll a gym</h2>
        <div>
          <Label>Gym name</Label>
          <Input value={name} onChange={(e) => setName(e.target.value)} required placeholder="H13 Gym" />
        </div>
        <div>
          <Label>Slug (URL)</Label>
          <Input
            value={slug}
            onChange={(e) => setSlug(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ''))}
            required
            placeholder="h13gym"
          />
          <p className="mt-1 text-xs text-muted">Public path: /g/{slug || '…'}</p>
        </div>
        <div>
          <Label>Display name (large brand)</Label>
          <Input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="H13Gym" />
        </div>
        <div>
          <Label>Owner full name</Label>
          <Input value={ownerFullName} onChange={(e) => setOwnerFullName(e.target.value)} required />
        </div>
        <div>
          <Label>Owner username</Label>
          <Input value={ownerUsername} onChange={(e) => setOwnerUsername(e.target.value)} required />
        </div>
        <div>
          <Label>Owner email</Label>
          <Input type="email" value={ownerEmail} onChange={(e) => setOwnerEmail(e.target.value)} required />
        </div>
        <div>
          <Label>Owner password</Label>
          <Input
            type="password"
            value={ownerPassword}
            onChange={(e) => setOwnerPassword(e.target.value)}
            required
            minLength={10}
          />
        </div>
        {enroll.error ? <QueryError error={enroll.error} /> : null}
        {created ? (
          <p className="text-sm text-ok">
            Created {created.tenant.displayName}. Owner login: {created.ownerUsername}. Public:{' '}
            <Link className="underline" to={gymPath(created.tenant.slug)}>
              /g/{created.tenant.slug}
            </Link>
          </p>
        ) : null}
        <Button type="submit" disabled={enroll.isPending}>
          Enroll gym
        </Button>
      </form>
    </div>
  )
}
