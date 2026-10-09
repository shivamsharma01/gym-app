import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Button, Card, Input, PageHeader, Table, TableShell, THead, Th, Td, Tr } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
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
    },
  })

  if (reviews.error) return <QueryError error={reviews.error} />

  return (
    <div className="space-y-6">
      <PageHeader
        title="Review"
        description="Server, reader, and baseline for each open difference. Nothing is linked automatically."
        actions={
          has('DEVICE_MANAGE') ? (
            <Button type="button" disabled={bootstrap.isPending} onClick={() => bootstrap.mutate()}>
              Bootstrap report
            </Button>
          ) : null
        }
      />
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
                <Th>Device user</Th>
                <Th>Server</Th>
                <Th>Reader</Th>
                <Th>Baseline</Th>
                <Th>Decision</Th>
              </Tr>
            </THead>
            <tbody>
              {(reviews.data ?? []).map((item) => {
                const actions = decisionCell(item, has('REVIEW_DECIDE'), decide.isPending, (path, body) =>
                  decide.mutate({ id: item.id, path, body }),
                )
                return (
                  <Tr key={item.id}>
                    <Td className="font-mono text-xs">{item.deviceUserId}</Td>
                    <Td>
                      <div>{item.serverName || '—'}</div>
                      <Validity from={item.serverValidFrom} to={item.serverValidTo} />
                    </Td>
                    <Td>
                      <div>{item.readerAbsent ? 'Absent' : item.readerName || '—'}</div>
                      <Validity from={item.readerValidFrom} to={item.readerValidTo} />
                    </Td>
                    <Td>
                      <div>{item.baselineName || '—'}</div>
                      <Validity from={item.baselineValidFrom} to={item.baselineValidTo} />
                    </Td>
                    <Td>{actions}</Td>
                  </Tr>
                )
              })}
            </tbody>
          </Table>
        </TableShell>
      </Card>
    </div>
  )
}

function Validity({ from, to }: { from: string | null; to: string | null }) {
  if (!from && !to) return null
  return (
    <div className="font-mono text-xs text-zinc-500">
      {from ?? '…'} – {to ?? '…'}
    </div>
  )
}

function decisionCell(
  item: ReviewItem,
  canManage: boolean,
  pending: boolean,
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
    return <RowActions item={item} pending={pending} onDecide={onDecide} />
  }
  return '—'
}

function RowActions({
  item,
  pending,
  onDecide,
}: Readonly<{
  item: ReviewItem
  pending: boolean
  onDecide: (path: string, body?: unknown) => void
}>) {
  const [memberId, setMemberId] = useState('')
  if (item.kind === 'ENROLLMENT') {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" size="sm" disabled={pending} onClick={() => onDecide('create')}>
          Create member
        </Button>
        <Button type="button" size="sm" variant="danger" disabled={pending} onClick={() => onDecide('reject')}>
          Reject
        </Button>
        <Input
          aria-label={`Member id for ${item.deviceUserId}`}
          value={memberId}
          onChange={(event) => setMemberId(event.target.value)}
          placeholder="Member id"
          className="w-40"
        />
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={pending || memberId.trim() === ''}
          onClick={() => onDecide('link', { memberId: memberId.trim() })}
        >
          Link
        </Button>
      </div>
    )
  }
  return (
    <div className="flex flex-wrap gap-2">
      <Button type="button" size="sm" disabled={pending} onClick={() => onDecide('accept-server')}>
        Accept server
      </Button>
      <Button type="button" size="sm" variant="outline" disabled={pending} onClick={() => onDecide('restore')}>
        Restore
      </Button>
      <Button type="button" size="sm" variant="danger" disabled={pending} onClick={() => onDecide('remove')}>
        Remove from this reader
      </Button>
    </div>
  )
}
