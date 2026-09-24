import { useQuery } from '@tanstack/react-query'
import { Users } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { QueryError } from '@/components/QueryError'
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  Skeleton,
  Table,
  TableShell,
  THead,
  Th,
  Td,
  Tr,
  Toolbar,
} from '@/components/ui'
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
      <Toolbar>
        <Input
          placeholder="Search members…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          className="max-w-md"
          aria-label="Search members"
        />
      </Toolbar>
      {members.isLoading ? <Skeleton className="h-48" /> : null}
      {members.error ? <QueryError error={members.error} onRetry={() => void members.refetch()} /> : null}
      {members.data && members.data.content.length === 0 ? (
        <EmptyState
          title="No members"
          body="Add a member to start memberships, payments, and device access."
          icon={<Users className="h-5 w-5" />}
          action={
            has('MEMBER_CREATE') ? (
              <Link to="/app/members/new">
                <Button size="sm">Add member</Button>
              </Link>
            ) : undefined
          }
        />
      ) : null}
      {members.data && members.data.content.length > 0 ? (
        <TableShell>
          <Table>
            <THead>
              <tr>
                <Th>Member</Th>
                <Th>Code</Th>
                <Th>Phone</Th>
                <Th>Source</Th>
                <Th>Status</Th>
              </tr>
            </THead>
            <tbody>
              {members.data.content.map((m) => (
                <Tr key={m.id}>
                  <Td>
                    <Link className="font-semibold text-ink hover:text-accent" to={`/app/members/${m.id}`}>
                      {m.fullName}
                    </Link>
                    {m.email ? <div className="mt-0.5 text-xs text-muted">{m.email}</div> : null}
                  </Td>
                  <Td className="font-mono text-xs text-muted">{m.memberCode}</Td>
                  <Td className="text-muted">{m.phone ?? '—'}</Td>
                  <Td>
                    <Badge tone={m.creationSource === 'DEVICE_IMPORT' ? 'warn' : 'muted'}>
                      {m.creationSource === 'DEVICE_IMPORT' ? 'Device' : 'Manual'}
                    </Badge>
                  </Td>
                  <Td>
                    <Badge tone={statusTone(m.status)}>{m.status}</Badge>
                  </Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        </TableShell>
      ) : null}
    </div>
  )
}
