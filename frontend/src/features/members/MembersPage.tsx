import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Users } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { PageNav } from '@/components/Pager'
import { QueryError } from '@/components/QueryError'
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  Select,
  Skeleton,
  SortableTh,
  type SortDirection,
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
import { accountStatusLabel, membershipStatusLabel, statusTone } from '@/lib/status'
import type { Member, PageResponse } from '@/lib/types'

type SortKey = 'name' | 'serial'

export function MembersPage() {
  const { has } = useAuth()
  const [q, setQ] = useState('')
  const [debounced, setDebounced] = useState('')
  const [status, setStatus] = useState<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL')
  const [page, setPage] = useState(0)
  const [sort, setSort] = useState<{ key: SortKey; direction: SortDirection } | null>(null)
  const toggleSort = (key: SortKey) => {
    // asc → desc → back to the default order (newest first)
    setSort((current) =>
      current?.key !== key ? { key, direction: 'asc' } : current.direction === 'asc' ? { key, direction: 'desc' } : null,
    )
    setPage(0)
  }
  const directionFor = (key: SortKey) => (sort?.key === key ? sort.direction : undefined)
  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(q)
      setPage(0)
    }, 300)
    return () => clearTimeout(t)
  }, [q])

  const members = useQuery({
    queryKey: ['members', debounced, status, page, sort],
    placeholderData: keepPreviousData,
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '25' })
      if (debounced.trim()) params.set('q', debounced)
      if (status !== 'ALL') params.set('status', status)
      if (sort) {
        params.set('sort', sort.key)
        params.set('direction', sort.direction)
      }
      return api<PageResponse<Member>>(`/api/v1/members?${params}`)
    },
  })

  return (
    <div>
      <PageHeader
        title="Members"
        description="Search by name, phone, serial, or member code. Click Member or Serial to sort."
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
          placeholder="Search by name, phone, serial or code…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          className="max-w-md"
          aria-label="Search members"
        />
        <Select
          aria-label="Account status"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as 'ALL' | 'ACTIVE' | 'INACTIVE')
            setPage(0)
          }}
          className="w-48"
        >
          <option value="ALL">All account statuses</option>
          <option value="ACTIVE">Account active</option>
          <option value="INACTIVE">Account inactive</option>
        </Select>
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
                <SortableTh direction={directionFor('name')} onSort={() => toggleSort('name')}>
                  Member
                </SortableTh>
                <SortableTh direction={directionFor('serial')} onSort={() => toggleSort('serial')}>
                  Serial
                </SortableTh>
                <Th>Code</Th>
                <Th>Phone</Th>
                <Th>Terminal Level</Th>
                <Th>Source</Th>
                <Th>Membership status</Th>
                <Th>Account status</Th>
              </tr>
            </THead>
            <tbody>
              {members.data.content.map((m) => (
                <Tr key={m.id}>
                  <Td>
                    <div className="flex items-center gap-1.5">
                      <Link className="font-semibold text-ink hover:text-accent" to={`/app/members/${m.id}`}>
                        {m.fullName}
                      </Link>
                      {m.deviceAuthority === 'ADMIN' ? (
                        <Badge tone="danger">Admin</Badge>
                      ) : null}
                    </div>
                    {m.email ? <div className="mt-0.5 text-xs text-muted">{m.email}</div> : null}
                  </Td>
                  <Td className="font-mono text-xs">{m.serialNumber ?? '—'}</Td>
                  <Td className="font-mono text-xs text-muted">{m.memberCode}</Td>
                  <Td className="text-muted">{m.phone ?? '—'}</Td>
                  <Td>
                    <Badge tone={m.deviceAuthority === 'ADMIN' ? 'danger' : 'muted'}>
                      {m.deviceAuthority === 'ADMIN' ? 'Admin' : 'User'}
                    </Badge>
                  </Td>
                  <Td>
                    <Badge tone={m.creationSource === 'DEVICE_IMPORT' ? 'warn' : 'muted'}>
                      {m.creationSource === 'DEVICE_IMPORT' ? 'Device' : 'Manual'}
                    </Badge>
                  </Td>
                  <Td>
                    <Badge tone={statusTone(m.coverageStatus)}>{membershipStatusLabel(m.coverageStatus)}</Badge>
                  </Td>
                  <Td>
                    <Badge tone={statusTone(m.status)}>{accountStatusLabel(m.status)}</Badge>
                  </Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        </TableShell>
      ) : null}
      {members.data && members.data.content.length > 0 ? (
        <PageNav data={members.data} onPageChange={setPage} />
      ) : null}
    </div>
  )
}
