import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Badge, Button, Card, EmptyState, Select } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { formatDate, money } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Membership, PageResponse, Plan } from '@/lib/types'

export function MembershipPanel({ memberId, rows }: { memberId: string; rows: Membership[] }) {
  const { has } = useAuth()
  const qc = useQueryClient()
  const plans = useQuery({
    queryKey: ['plans'],
    queryFn: () => api<PageResponse<Plan>>('/api/v1/plans?size=50'),
    enabled: has('MEMBERSHIP_CREATE'),
  })
  const [planId, setPlanId] = useState('')
  const create = useMutation({
    mutationFn: () => api<Membership>('/api/v1/memberships', { method: 'POST', body: JSON.stringify({ memberId, planId }) }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['memberships', memberId] })
      void qc.invalidateQueries({ queryKey: ['access', memberId] })
    },
  })
  const act = useMutation({
    mutationFn: ({ id, path, body }: { id: string; path: string; body?: unknown }) =>
      api(`/api/v1/memberships/${id}/${path}`, {
        method: 'POST',
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['memberships', memberId] })
      void qc.invalidateQueries({ queryKey: ['access', memberId] })
    },
  })

  return (
    <div className="space-y-4">
      {has('MEMBERSHIP_CREATE') ? (
        <div className="flex flex-col gap-2 sm:flex-row">
          <Select value={planId} onChange={(e) => setPlanId(e.target.value)}>
            <option value="">Select a plan</option>
            {plans.data?.content
              .filter((p) => p.status === 'ACTIVE')
              .map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
          </Select>
          <Button disabled={!planId || create.isPending} onClick={() => create.mutate()}>
            Start membership
          </Button>
        </div>
      ) : null}
      {create.error ? <QueryError error={create.error} /> : null}
      {act.error ? <QueryError error={act.error} /> : null}
      {!rows.length ? <EmptyState title="No memberships" body="Start a plan for this member." /> : null}
      {rows.map((row) => (
        <Card key={row.id} className="space-y-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="font-semibold">{row.planName}</div>
            <Badge tone={statusTone(row.effectiveStatus)}>{row.effectiveStatus}</Badge>
          </div>
          <p className="text-sm text-muted">
            {formatDate(row.startDate)} → {formatDate(row.endDate)} · {money(row.price, row.currency)} · paid{' '}
            {money(row.amountPaid, row.currency)} · device {row.deviceSyncState}
          </p>
          <div className="flex flex-wrap gap-2">
            {has('MEMBERSHIP_FREEZE') && row.status === 'ACTIVE' ? (
              <Button variant="outline" onClick={() => act.mutate({ id: row.id, path: 'freeze' })}>
                Freeze
              </Button>
            ) : null}
            {has('MEMBERSHIP_FREEZE') && row.status === 'FROZEN' ? (
              <Button variant="outline" onClick={() => act.mutate({ id: row.id, path: 'unfreeze' })}>
                Unfreeze
              </Button>
            ) : null}
            {has('MEMBERSHIP_CREATE') ? (
              <Button variant="outline" onClick={() => act.mutate({ id: row.id, path: 'renew', body: {} })}>
                Renew
              </Button>
            ) : null}
            {has('MEMBERSHIP_CANCEL') && row.status !== 'CANCELLED' ? (
              <Button
                variant="danger"
                onClick={() => {
                  const reason = prompt('Cancel reason (optional)') ?? ''
                  act.mutate({ id: row.id, path: 'cancel', body: { reason } })
                }}
              >
                Cancel
              </Button>
            ) : null}
          </div>
        </Card>
      ))}
    </div>
  )
}
