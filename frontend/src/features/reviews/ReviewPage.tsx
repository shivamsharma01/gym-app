import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Button, Card, Input, PageHeader, Table, TableShell, THead, Th, Td, Tr } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/useAuth'
import type { BootstrapReport, ReviewItem } from '@/lib/types'

export function ReviewPage() {
  const { has } = useAuth()
  const qc = useQueryClient()
  const reviews = useQuery({
    queryKey: ['reviews'],
    queryFn: () => api<ReviewItem[]>('/api/v1/reviews'),
  })
  const [report, setReport] = useState<BootstrapReport | null>(null)
  const bootstrap = useMutation({
    mutationFn: () => api<BootstrapReport>('/api/v1/reviews/bootstrap', { method: 'POST', body: '{}' }),
    onSuccess: (next) => {
      setReport(next)
      void qc.invalidateQueries({ queryKey: ['reviews'] })
    },
  })
  const decide = useMutation({
    mutationFn: (action: { id: string; path: string; body?: unknown }) =>
      api<ReviewItem>(`/api/v1/reviews/${action.id}/${action.path}`, {
        method: 'POST',
        body: JSON.stringify(action.body ?? {}),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['reviews'] })
      void qc.invalidateQueries({ queryKey: ['members'] })
      void qc.invalidateQueries({ queryKey: ['member'] })
      void qc.invalidateQueries({ queryKey: ['member-device-sync'] })
    },
  })

  if (reviews.error) return <QueryError error={reviews.error} />

  return (
    <div className="space-y-6">
      <PageHeader
        title="Review"
        description="Each row is one reader’s observation (name, dates, status, or photo). Photo compares to the last hash read from that device, so re-encodes after a server write do not open noise. Keep reader values adopts the edit; Create as new releases that id for a different person."
        actions={
          has('DEVICE_MANAGE') ? (
            <Button type="button" disabled={bootstrap.isPending} onClick={() => bootstrap.mutate()}>
              Bootstrap report
            </Button>
          ) : null
        }
      />
      {decide.error ? <QueryError error={decide.error} /> : null}
      {report ? (
        <Card padded={false}>
          <TableShell>
            <Table>
              <THead>
                <Tr>
                  <Th>Run</Th>
                  <Th>Outcome</Th>
                  <Th>Device user</Th>
                  <Th>Reader</Th>
                  <Th>Server</Th>
                  <Th>Suggestion</Th>
                </Tr>
              </THead>
              <tbody>
                {report.rows.map((row) => (
                  <Tr key={`${row.outcome}-${row.deviceId}-${row.deviceUserId}`}>
                    <Td className="font-mono text-xs">{report.runId}</Td>
                    <Td>{row.outcome}</Td>
                    <Td className="font-mono text-xs">{row.deviceUserId}</Td>
                    <Td>{row.readerName || '—'}</Td>
                    <Td>{row.serverName || '—'}</Td>
                    <Td className="font-mono text-xs">{row.suggestionMemberId || '—'}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
          </TableShell>
        </Card>
      ) : null}
      <Card padded={false}>
        <TableShell>
          <Table>
            <THead>
              <Tr>
                <Th>Reader</Th>
                <Th>Device user</Th>
                <Th>Server</Th>
                <Th>On the reader</Th>
                <Th>Baseline</Th>
                <Th>Decision</Th>
              </Tr>
            </THead>
            {groupByMember(reviews.data ?? []).map((group) => (
              <tbody key={group.key} aria-label={group.title}>
                <Tr>
                  <Td colSpan={6} className="bg-raised text-sm font-semibold">
                    {group.title}
                    {group.items.length > 1 ? (
                      <span className="ml-2 text-xs font-normal text-muted">
                        {group.items.length} items — approving keep server/reader syncs all mapped readers
                      </span>
                    ) : null}
                  </Td>
                </Tr>
                {group.items.map((item) => {
                  const suggestion = linkSuggestion(item, report?.rows, reviews.data ?? [])
                  const actions = decisionCell(item, has('REVIEW_DECIDE'), decide.isPending, suggestion, (path, body) =>
                    decide.mutate({ id: item.id, path, body }),
                  )
                  return (
                    <Tr key={item.id}>
                      <Td className="text-xs">{item.deviceName || item.deviceId}</Td>
                      <Td className="font-mono text-xs">{item.deviceUserId}</Td>
                      <Td>
                        <div>{item.serverName || '—'}</div>
                        <Validity from={item.serverValidFrom} to={item.serverValidTo} />
                        <Access status={item.serverUserStatus} />
                      </Td>
                      <Td>
                        <div>{item.readerAbsent ? 'Absent' : item.readerName || '—'}</div>
                        <Validity from={item.readerValidFrom} to={item.readerValidTo} />
                        <Access status={item.readerUserStatus} />
                        {item.readerPhotoDiffers ? (
                          <div className="text-xs text-warn">Photo differs from the server photo</div>
                        ) : null}
                        {item.kind === 'SUSPENDED' ? (
                          <div className="text-xs text-warn">
                            Someone else holds this id; the member is off this reader
                          </div>
                        ) : null}
                        {item.kind === 'ENROLLMENT' && item.possibleMemberId ? (
                          <div className="text-xs text-warn">
                            May already be {item.possibleMemberName || item.possibleMemberId}; link instead of creating
                          </div>
                        ) : null}
                      </Td>
                      <Td>
                        <div>{item.baselineName || '—'}</div>
                        <Validity from={item.baselineValidFrom} to={item.baselineValidTo} />
                        <Access status={item.baselineUserStatus} />
                      </Td>
                      <Td>{actions}</Td>
                    </Tr>
                  )
                })}
              </tbody>
            ))}
          </Table>
        </TableShell>
      </Card>
    </div>
  )
}

type ReviewGroup = { key: string; title: string; items: ReviewItem[] }

/** Prefer a real member UUID suggestion; never treat the reader device user id as a member id. */
function linkSuggestion(
  item: ReviewItem,
  reportRows: BootstrapReport['rows'] | undefined,
  openItems: ReviewItem[],
): string | null {
  const fromReport = reportRows?.find(
    (row) => row.deviceId === item.deviceId && row.deviceUserId === item.deviceUserId,
  )?.suggestionMemberId
  const fromPossible = item.possibleMemberId
  const fromSuspended = openItems.find(
    (row) =>
      row.kind === 'SUSPENDED' &&
      row.deviceId === item.deviceId &&
      row.deviceUserId === item.deviceUserId &&
      row.memberId,
  )?.memberId
  const candidate = fromReport || fromPossible || fromSuspended || null
  if (!candidate || candidate === item.deviceUserId) return null
  return candidate
}

/** One section per member; each reader's item in it is still decided on its own. Unknown people come last. */
function groupByMember(items: ReviewItem[]): ReviewGroup[] {
  const groups = new Map<string, ReviewGroup>()
  const unknown: ReviewItem[] = []
  for (const item of items) {
    if (item.kind === 'ENROLLMENT') {
      unknown.push(item)
      continue
    }
    const key = item.memberId ?? `item-${item.id}`
    const group = groups.get(key) ?? { key, title: `Member: ${item.memberName || item.serverName || '—'}`, items: [] }
    group.items.push(item)
    groups.set(key, group)
  }
  const sections = [...groups.values()]
  if (unknown.length > 0) {
    sections.push({ key: 'unknown', title: 'People the server does not know', items: unknown })
  }
  return sections
}

function Validity({ from, to }: Readonly<{ from: string | null; to: string | null }>) {
  if (!from && !to) return null
  return (
    <div className="font-mono text-xs text-zinc-500">
      {from ?? '…'} – {to ?? '…'}
    </div>
  )
}

function Access({ status }: Readonly<{ status: number | null }>) {
  if (status == null) return null
  return <div className="text-xs text-zinc-500">{status === 0 ? 'Enabled' : 'Disabled'}</div>
}

function decisionCell(
  item: ReviewItem,
  canManage: boolean,
  pending: boolean,
  suggestion: string | null | undefined,
  onDecide: (path: string, body?: unknown) => void,
) {
  if (item.decision) {
    return (
      <div className="space-y-1 text-sm">
        <div>{item.decision}</div>
        <div className="text-xs text-muted">
          {item.actor} · {item.priorState} → {item.chosenState}
          {item.revision != null ? ` · revision ${item.revision}` : ''}
        </div>
        {item.verificationError ? <div className="text-xs text-danger">{item.verificationError}</div> : null}
      </div>
    )
  }
  if (canManage) {
    return (
      <RowActions
        key={suggestion ?? ''}
        item={item}
        pending={pending}
        suggestion={suggestion}
        onDecide={onDecide}
      />
    )
  }
  return '—'
}

function RowActions({
  item,
  pending,
  suggestion,
  onDecide,
}: Readonly<{
  item: ReviewItem
  pending: boolean
  suggestion: string | null | undefined
  onDecide: (path: string, body?: unknown) => void
}>) {
  const [memberId, setMemberId] = useState(suggestion ?? '')
  if (item.kind === 'ENROLLMENT') {
    return (
      <div className="flex flex-col gap-2">
        <p className="max-w-sm text-xs text-muted">
          Link needs the server member UUID from the member page URL (e.g. /app/members/…), not reader id{' '}
          {item.deviceUserId}. Link keeps the reader&apos;s name, dates, and photo on that member.
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" size="sm" disabled={pending} onClick={() => onDecide('create')}>
            Create member
          </Button>
          <Button type="button" size="sm" variant="danger" disabled={pending} onClick={() => onDecide('reject')}>
            Reject
          </Button>
          <Input
            aria-label={`Server member UUID for ${item.deviceUserId}`}
            value={memberId}
            onChange={(event) => setMemberId(event.target.value)}
            placeholder="Server member UUID"
            className="w-56 font-mono text-xs"
          />
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={pending || memberId.trim() === '' || memberId.trim() === item.deviceUserId}
            title={
              memberId.trim() === item.deviceUserId
                ? 'That is the reader id; paste the member UUID from the member page'
                : undefined
            }
            onClick={() => onDecide('link', { memberId: memberId.trim() })}
          >
            Link
          </Button>
        </div>
      </div>
    )
  }
  if (item.kind === 'SUSPENDED') {
    return (
      <div className="flex flex-col gap-2">
        <p className="max-w-xs text-xs text-muted">
          Name and photo both differ from what was last verified, so this looked like a different person. If it is
          still {item.memberName || 'this member'}, adopt the reader values — do not create a new member.
        </p>
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            size="sm"
            disabled={pending}
            title="Keep this member; take name, dates, and photo from the reader at the same id"
            onClick={() => onDecide('adopt-reader')}
          >
            Keep as this member (reader wins)
          </Button>
          <Button type="button" size="sm" variant="outline" disabled={pending} onClick={() => onDecide('restore')}>
            Restore under a new id
          </Button>
          <Button type="button" size="sm" variant="outline" disabled={pending} onClick={() => onDecide('remove')}>
            Keep off this reader
          </Button>
        </div>
      </div>
    )
  }
  return (
    <div className="flex flex-col gap-2">
      <p className="max-w-sm text-xs text-muted">
        Device edits stay on this member until you decide. Keep reader values if it is the same person; Create as
        new if someone else is at this id.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button
          type="button"
          size="sm"
          disabled={pending || item.readerAbsent}
          title="Approve the reader's values as the member record and sync them to all mapped readers"
          onClick={() => onDecide('accept-reader')}
        >
          Keep reader values
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={pending}
          title="Write the server record onto this reader and sync it to all mapped readers"
          onClick={() => onDecide('accept-server')}
        >
          Overwrite with server
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={pending || item.readerAbsent}
          title="Release this member from the id and create a new member from what the reader shows"
          onClick={() => onDecide('create-as-new')}
        >
          Create as new
        </Button>
        <Button
          type="button"
          size="sm"
          variant="danger"
          disabled={pending}
          title="Remove this member from this reader only"
          onClick={() => onDecide('remove')}
        >
          Remove from this reader
        </Button>
      </div>
    </div>
  )
}
