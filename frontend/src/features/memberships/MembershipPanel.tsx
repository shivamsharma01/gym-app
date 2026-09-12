import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { Badge, Button, Card, EmptyState, Input, Label, Select } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { formatDate, money } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Membership, PageResponse, Plan } from '@/lib/types'

function todayIso() {
  const d = new Date()
  return toIso(d.getFullYear(), d.getMonth() + 1, d.getDate())
}

function toIso(year: number, month: number, day: number) {
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

function addDays(iso: string, days: number) {
  const [y, m, d] = iso.split('-').map(Number)
  const dt = new Date(y, m - 1, d)
  dt.setDate(dt.getDate() + days)
  return toIso(dt.getFullYear(), dt.getMonth() + 1, dt.getDate())
}

export function MembershipPanel({ memberId, rows }: { memberId: string; rows: Membership[] }) {
  const { has } = useAuth()
  const qc = useQueryClient()
  const plans = useQuery({
    queryKey: ['plans'],
    queryFn: () => api<PageResponse<Plan>>('/api/v1/plans?size=50'),
    enabled: has('MEMBERSHIP_CREATE') || has('MEMBERSHIP_UPDATE'),
  })
  const [planId, setPlanId] = useState('')
  const [startDate, setStartDate] = useState(todayIso())
  const [endDate, setEndDate] = useState('')
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editStart, setEditStart] = useState('')
  const [editEnd, setEditEnd] = useState('')

  const selectedPlan = (plans.data?.content ?? []).find((p) => p.id === planId)

  function applyPlan(id: string) {
    setPlanId(id)
    const plan = (plans.data?.content ?? []).find((p) => p.id === id)
    const start = startDate || todayIso()
    setStartDate(start)
    if (plan) setEndDate(addDays(start, Math.max(plan.durationDays - 1, 0)))
  }

  function applyStart(next: string) {
    setStartDate(next)
    if (selectedPlan) setEndDate(addDays(next, Math.max(selectedPlan.durationDays - 1, 0)))
  }

  const create = useMutation({
    mutationFn: () =>
      api<Membership>('/api/v1/memberships', {
        method: 'POST',
        body: JSON.stringify({ memberId, planId, startDate, endDate }),
      }),
    onSuccess: () => {
      setPlanId('')
      setStartDate(todayIso())
      setEndDate('')
      void qc.invalidateQueries({ queryKey: ['memberships', memberId] })
      void qc.invalidateQueries({ queryKey: ['access', memberId] })
    },
  })
  const saveDates = useMutation({
    mutationFn: () =>
      api<Membership>(`/api/v1/memberships/${editingId}/dates`, {
        method: 'PUT',
        body: JSON.stringify({ startDate: editStart, endDate: editEnd }),
      }),
    onSuccess: () => {
      setEditingId(null)
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

  const activePlans = (plans.data?.content ?? []).filter((p) => p.status === 'ACTIVE')

  return (
    <div className="space-y-4">
      {has('MEMBERSHIP_CREATE') ? (
        activePlans.length === 0 ? (
          <p className="text-sm text-muted">
            No plans yet.{' '}
            <Link className="underline" to="/app/plans">
              Create a plan
            </Link>{' '}
            (name, price, duration), then come back here to start it for this member.
          </p>
        ) : (
          <div className="space-y-3 rounded-xl border border-line p-3">
            <Select value={planId} onChange={(e) => applyPlan(e.target.value)}>
              <option value="">Select a plan</option>
              {activePlans.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} · {p.durationDays} days
                </option>
              ))}
            </Select>
            {planId ? (
              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <Label htmlFor="membership-start">Start</Label>
                  <Input
                    id="membership-start"
                    type="date"
                    value={startDate}
                    onChange={(e) => applyStart(e.target.value)}
                  />
                </div>
                <div>
                  <Label htmlFor="membership-end">End</Label>
                  <Input id="membership-end" type="date" value={endDate} min={startDate} onChange={(e) => setEndDate(e.target.value)} />
                </div>
              </div>
            ) : null}
            <Button disabled={!planId || !startDate || !endDate || create.isPending} onClick={() => create.mutate()}>
              Start membership
            </Button>
          </div>
        )
      ) : null}
      {plans.error ? <QueryError error={plans.error} /> : null}
      {create.error ? <QueryError error={create.error} /> : null}
      {saveDates.error ? <QueryError error={saveDates.error} /> : null}
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
          {editingId === row.id ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <Label>Start</Label>
                <Input type="date" value={editStart} onChange={(e) => setEditStart(e.target.value)} />
              </div>
              <div>
                <Label>End</Label>
                <Input type="date" value={editEnd} min={editStart} onChange={(e) => setEditEnd(e.target.value)} />
              </div>
              <div className="flex gap-2 sm:col-span-2">
                <Button disabled={saveDates.isPending || !editStart || !editEnd} onClick={() => saveDates.mutate()}>
                  Save dates
                </Button>
                <Button variant="ghost" type="button" onClick={() => setEditingId(null)}>
                  Cancel
                </Button>
              </div>
            </div>
          ) : null}
          <div className="flex flex-wrap gap-2">
            {has('MEMBERSHIP_UPDATE') && row.status !== 'CANCELLED' && row.status !== 'FROZEN' && editingId !== row.id ? (
              <Button
                variant="outline"
                onClick={() => {
                  setEditingId(row.id)
                  setEditStart(row.startDate)
                  setEditEnd(row.endDate)
                }}
              >
                Change dates
              </Button>
            ) : null}
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
