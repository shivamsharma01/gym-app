import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { QueryError } from '@/components/QueryError'
import { Badge, Button, EmptyState, Input, PageHeader, Skeleton } from '@/components/ui'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { statusTone } from '@/lib/status'
import type { Member, PageResponse } from '@/lib/types'

export function MembersPage() {
  const { has } = useAuth()
  const [q, setQ] = useState('')
  const [debounced, setDebounced] = useState('')
  useEffect(() => {
    const t = setTimeout(() => setDebounced(q), 300)
    return () => clearTimeout(t)
  }, [q])

  const members = useQuery({
    queryKey: ['members', debounced],
    queryFn: () =>
      api<PageResponse<Member>>(`/api/v1/members?page=0&size=50${debounced ? `&q=${encodeURIComponent(debounced)}` : ''}`),
  })

  return (
    <div>
      <PageHeader
        title="Members"
        description="Search by name, phone, or member code."
        actions={
          has('MEMBER_CREATE') ? (
            <Link to="/app/members/new">
              <Button>Add member</Button>
            </Link>
          ) : null
        }
      />
      <Input placeholder="Search members" value={q} onChange={(e) => setQ(e.target.value)} className="mb-6 max-w-md" />
      {members.isLoading ? <Skeleton className="h-48" /> : null}
      {members.error ? <QueryError error={members.error} /> : null}
      {members.data && members.data.content.length === 0 ? (
        <EmptyState title="No members" body="Add a member to start memberships, payments, and device access." />
      ) : null}
      {members.data && members.data.content.length > 0 ? (
        <div className="overflow-x-auto rounded-xl border border-line">
          <table className="w-full min-w-[640px] text-left text-sm">
            <thead className="bg-raised text-xs uppercase tracking-wide text-muted">
              <tr>
                <th className="px-4 py-3">Code</th>
                <th className="px-4 py-3">Name</th>
                <th className="px-4 py-3">Phone</th>
                <th className="px-4 py-3">Status</th>
              </tr>
            </thead>
            <tbody>
              {members.data.content.map((m) => (
                <tr key={m.id} className="border-t border-line hover:bg-raised/60">
                  <td className="px-4 py-3 font-mono text-xs">{m.memberCode}</td>
                  <td className="px-4 py-3">
                    <Link className="font-semibold hover:underline" to={`/app/members/${m.id}`}>
                      {m.fullName}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-muted">{m.phone ?? '—'}</td>
                  <td className="px-4 py-3">
                    <Badge tone={statusTone(m.status)}>{m.status}</Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  )
}
