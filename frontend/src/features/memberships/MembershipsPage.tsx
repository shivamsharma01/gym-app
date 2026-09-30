import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link, useParams } from 'react-router'
import { PageNav } from '@/components/Pager'
import { QueryError } from '@/components/QueryError'
import {
  Badge,
  Card,
  EmptyState,
  PageHeader,
  Skeleton,
  Table,
  TableShell,
  THead,
  Th,
  Td,
  Tr,
} from '@/components/ui'
import { api } from '@/lib/api'
import { formatDate, money } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { PageResponse } from '@/lib/types'

export type PlanRoster = {
  id: string
  name: string
  description: string | null
  price: number | string
  currency: string
  durationDays: number
  status: string
  activeMembers: number
  expiringWithin7Days: number
}

export type PlanActiveMember = {
  memberId: string
  memberCode: string
  fullName: string
  phone: string | null
  startDate: string
  endDate: string
  expiringWithin7Days: boolean
}

export function MembershipsPage() {
  const plans = useQuery({
    queryKey: ['membership-plans-roster'],
    queryFn: () => api<PlanRoster[]>('/api/v1/memberships/plans'),
  })

  return (
    <div className="space-y-6">
      <PageHeader
        title="Memberships"
        description="Each plan, who is on it now, and who reaches the end date within 7 days."
      />
      {plans.isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2">
          <Skeleton className="h-40" />
          <Skeleton className="h-40" />
        </div>
      ) : null}
      {plans.error ? <QueryError error={plans.error} onRetry={() => void plans.refetch()} /> : null}
      {plans.data && plans.data.length === 0 ? (
        <EmptyState title="No plans" body="Create a plan first. Members on that plan will show up here." />
      ) : null}
      {plans.data && plans.data.length > 0 ? (
        <div className="grid gap-4 sm:grid-cols-2">
          {plans.data.map((plan) => (
            <Link key={plan.id} to={`/app/memberships/${plan.id}`} className="block">
              <Card className="h-full space-y-3 transition hover:border-line-strong">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <div className="font-semibold">{plan.name}</div>
                    {plan.description ? <p className="mt-1 text-sm text-muted">{plan.description}</p> : null}
                  </div>
                  <Badge tone={statusTone(plan.status)}>{plan.status === 'ACTIVE' ? 'Open' : plan.status}</Badge>
                </div>
                <p className="text-sm text-muted">
                  {money(plan.price, plan.currency)} · {plan.durationDays} days
                </p>
                <div className="grid grid-cols-2 gap-3 border-t border-line pt-3">
                  <div>
                    <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">Active members</div>
                    <div className="mt-1 text-2xl font-semibold tabular-nums">{plan.activeMembers}</div>
                  </div>
                  <div>
                    <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">Expiring in 7 days</div>
                    <div className="mt-1 text-2xl font-semibold tabular-nums">{plan.expiringWithin7Days}</div>
                  </div>
                </div>
              </Card>
            </Link>
          ))}
        </div>
      ) : null}
    </div>
  )
}

export function MembershipPlanMembersPage() {
  const { planId } = useParams()
  const [page, setPage] = useState(0)
  const plans = useQuery({
    queryKey: ['membership-plans-roster'],
    queryFn: () => api<PlanRoster[]>('/api/v1/memberships/plans'),
  })
  const plan = plans.data?.find((row) => row.id === planId)
  const members = useQuery({
    queryKey: ['plan-active-members', planId, page],
    queryFn: () =>
      api<PageResponse<PlanActiveMember>>(
        `/api/v1/memberships/plans/${planId}/members?page=${page}&size=20`,
      ),
    enabled: Boolean(planId),
    placeholderData: keepPreviousData,
  })

  return (
    <div className="space-y-6">
      <Link
        to="/app/memberships"
        className="inline-flex items-center gap-1.5 text-sm text-muted transition-colors hover:text-ink"
      >
        <ArrowLeft className="h-4 w-4" />
        All plans
      </Link>
      <PageHeader
        title={plan?.name ?? 'Plan'}
        description={
          plan
            ? `${money(plan.price, plan.currency)} · ${plan.durationDays} days · ${plan.activeMembers} active · ${plan.expiringWithin7Days} expiring in 7 days`
            : 'Members whose membership on this plan covers today.'
        }
      />
      {plan?.description ? <p className="text-sm text-muted">{plan.description}</p> : null}
      {members.isLoading ? <Skeleton className="h-40" /> : null}
      {members.error ? <QueryError error={members.error} onRetry={() => void members.refetch()} /> : null}
      {members.data && members.data.content.length === 0 ? (
        <EmptyState title="No active members" body="Nobody is on this plan today." />
      ) : null}
      {members.data && members.data.content.length > 0 ? (
        <>
          <TableShell>
            <Table>
              <THead>
                <tr>
                  <Th>Member</Th>
                  <Th>Member ID</Th>
                  <Th>Phone</Th>
                  <Th>Start</Th>
                  <Th>End</Th>
                </tr>
              </THead>
              <tbody>
                {members.data.content.map((row) => (
                  <Tr key={`${row.memberId}-${row.endDate}`}>
                    <Td className="font-medium">
                      <Link className="hover:underline" to={`/app/members/${row.memberId}`}>
                        {row.fullName}
                      </Link>
                      {row.expiringWithin7Days ? <Badge tone="warn">Expiring</Badge> : null}
                    </Td>
                    <Td className="text-muted">{row.memberCode}</Td>
                    <Td className="text-muted">{row.phone || '—'}</Td>
                    <Td>{formatDate(row.startDate)}</Td>
                    <Td>{formatDate(row.endDate)}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
          </TableShell>
          <PageNav data={members.data} onPageChange={setPage} />
        </>
      ) : null}
    </div>
  )
}
