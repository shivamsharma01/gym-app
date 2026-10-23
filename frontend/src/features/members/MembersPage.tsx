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
import { useAuth } from '@/lib/useAuth'
import { accountStatusLabel, membershipStatusLabel, statusTone } from '@/lib/status'
import type { Member, PageResponse } from '@/lib/types'

type SortKey = 'name'

export function MembersPage() {
  const { has } = useAuth()
  const [q, setQ] = useState('')
  const [debounced, setDebounced] = useState('')
  const [status, setStatus] = useState<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL')
  const [page, setPage] = useState(0)
  const [deviceAuthority, setDeviceAuthority] = useState<'ALL' | 'ADMIN' | 'USER'>('ALL')
  const [idsDiffer, setIdsDiffer] = useState(false)
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
    queryKey: ['members', debounced, status, deviceAuthority, idsDiffer, page, sort],
    placeholderData: keepPreviousData,
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '25' })
      if (debounced.trim()) params.set('q', debounced)
      if (status !== 'ALL') params.set('status', status)
      if (deviceAuthority !== 'ALL') {
        params.set('deviceAuthority', deviceAuthority)
      }
      if (idsDiffer) params.set('idsDiffer', 'true')
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
        description="Search by name, phone, or member code. Click Member to sort."
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
          placeholder="Search by name, phone, or member code…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          className="max-w-md"
          aria-label="Search members"
        />
        <Select
            aria-label="Terminal user"
            value={deviceAuthority}
            onChange={(e) => {
              setDeviceAuthority(e.target.value as 'ALL' | 'ADMIN' | 'USER')
              setPage(0)
            }}
            className="w-48"
        >
          <option value="ALL">All terminal users</option>
          <option value="ADMIN">Admin</option>
          <option value="USER">User</option>
        </Select>
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
        <Button
          type="button"
          size="sm"
          variant={idsDiffer ? 'primary' : 'outline'}
          onClick={() => {
            setIdsDiffer((current) => !current)
            setPage(0)
          }}
        >
          Different reader ids
        </Button>
      </Toolbar>
      {members.isLoading ? <Skeleton className="h-48" /> : null}
      {members.error ? <QueryError error={members.error} onRetry={() => void members.refetch()} /> : null}
      {members.data && members.data.content.length === 0 ? (
        <EmptyState
          title={idsDiffer ? 'Ids match' : 'No members'}
          body={
            idsDiffer
              ? 'No member has a different id on one reader than on another.'
              : 'Add a member to start memberships, payments, and device access.'
          }
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
                {(members.data.content[0]?.readers ?? []).map((reader) => (
                  <Th key={reader.deviceId}>{reader.deviceName} id</Th>
                ))}
                <Th>Phone</Th>
                <Th>Terminal Level</Th>
                <Th>Membership status</Th>
                <Th>Account status</Th>
              </tr>
            </THead>
            <tbody>
              {members.data.content.map((m) => {
                const syncIssue = worstSyncBadge(m.readers)
                return (
                  <Tr key={m.id}>
                    <Td>
                      <div className="flex items-center gap-1.5">
                        <Link className="font-semibold text-ink hover:text-accent" to={`/app/members/${m.id}`}>
                          {m.fullName}
                        </Link>
                        {m.deviceAuthority === 'ADMIN' ? (
                          <Badge tone="danger">Admin</Badge>
                        ) : null}
                        {syncIssue ? <Badge tone={syncIssue.tone}>{syncIssue.label}</Badge> : null}
                      </div>
                      {m.email ? <div className="mt-0.5 text-xs text-muted">{m.email}</div> : null}
                    </Td>
                    {(m.readers ?? []).map((reader) => (
                      <Td
                        key={reader.deviceId}
                        className={`font-mono text-xs ${readerSyncCellClass(reader.syncState)}`}
                        title={readerSyncTitle(reader.syncState)}
                      >
                        <span className="inline-flex items-center gap-1.5">
                          {readerSyncDot(reader.syncState)}
                          {reader.deviceUserId ?? '—'}
                        </span>
                      </Td>
                    ))}
                    <Td className="text-muted">{m.phone ?? '—'}</Td>
                    <Td>
                      <Badge tone={m.deviceAuthority === 'ADMIN' ? 'danger' : 'muted'}>
                        {m.deviceAuthority === 'ADMIN' ? 'Admin' : 'User'}
                      </Badge>
                    </Td>
                    <Td>
                      <Badge tone={statusTone(m.coverageStatus)}>{membershipStatusLabel(m.coverageStatus)}</Badge>
                    </Td>
                    <Td>
                      <span title="Only Deactivate changes this. Validity dates do not.">
                        <Badge tone={statusTone(m.status)}>{accountStatusLabel(m.status)}</Badge>
                      </span>
                    </Td>
                  </Tr>
                )
              })}
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

type SyncBadge = { label: string; tone: 'warn' | 'danger' }

function worstSyncBadge(
  readers: Member['readers'] | undefined,
): SyncBadge | null {
  const states = (readers ?? []).map((r) => r.syncState)
  if (states.includes('failed')) return { label: 'Update failed', tone: 'danger' }
  if (states.includes('held')) return { label: 'Needs review', tone: 'warn' }
  if (states.includes('pending')) return { label: 'Waiting', tone: 'warn' }
  return null
}

function readerSyncCellClass(syncState: string | undefined) {
  if (syncState === 'failed') return 'bg-danger/10 text-danger'
  if (syncState === 'held') return 'bg-warn/10 text-warn'
  if (syncState === 'pending') return 'bg-warn/5 text-warn'
  return ''
}

function readerSyncTitle(syncState: string | undefined) {
  switch (syncState) {
    case 'failed':
      return 'The reader could not update this member'
    case 'held':
      return 'Paused. Decide in Review first'
    case 'pending':
      return 'Waiting for the reader to confirm'
    case 'absent':
      return 'Removed from this reader'
    case 'ok':
      return 'On reader'
    case 'seen':
      return 'Already on this reader'
    default:
      return undefined
  }
}

function readerSyncDot(syncState: string | undefined) {
  if (syncState !== 'failed' && syncState !== 'held' && syncState !== 'pending') return null
  const color =
    syncState === 'failed' ? 'bg-danger' : syncState === 'held' ? 'bg-warn' : 'bg-warn/70'
  return <span className={`inline-block h-1.5 w-1.5 shrink-0 rounded-full ${color}`} aria-hidden />
}
