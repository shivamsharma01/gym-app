import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { QueryError } from '@/components/QueryError'
import {
  Badge,
  Button,
  EmptyState,
  PageHeader,
  Skeleton,
  StatCard,
  Table,
  TableShell,
  THead,
  Th,
  Td,
  Tr,
} from '@/components/ui'
import { api } from '@/lib/api'
import { money } from '@/lib/cn'
import { statusTone } from '@/lib/status'

type Summary = {
  membersTotal: number
  membersActive: number
  membershipsActive: number
  membershipsExpiringSoon: number
  membershipsExpired: number
  paymentsCompletedAmount: number | string
  paymentsCompletedCount: number
  attendanceEvents: number
  accessDenied: number
  devices: { id: string; name: string; connectionState: string }[]
}

export function ReportsPage() {
  const summary = useQuery({
    queryKey: ['reports', 'summary'],
    queryFn: () => api<Summary>('/api/v1/reports/summary'),
  })

  return (
    <div>
      <PageHeader
        title="Reports"
        description="Server-side counts for the default report window (typically last 30 days)."
        actions={
          <div className="flex flex-wrap gap-2">
            <Link to="/app/reports/memberships">
              <Button variant="outline" size="sm">
                Membership snapshot
              </Button>
            </Link>
            <Link to="/app/reports/devices">
              <Button variant="outline" size="sm">
                Device health
              </Button>
            </Link>
          </div>
        }
      />
      {summary.isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-28" />
          ))}
        </div>
      ) : null}
      {summary.error ? <QueryError error={summary.error} onRetry={() => void summary.refetch()} /> : null}
      {summary.data ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="Members" value={summary.data.membersTotal} hint={`${summary.data.membersActive} active`} />
          <StatCard
            label="Active memberships"
            value={summary.data.membershipsActive}
            hint={`${summary.data.membershipsExpiringSoon} expiring in 7 days`}
          />
          <StatCard
            label="Payments"
            value={money(summary.data.paymentsCompletedAmount, 'INR')}
            hint={`${summary.data.paymentsCompletedCount} completed`}
          />
          <StatCard
            label="Attendance events"
            value={summary.data.attendanceEvents}
            hint={`${summary.data.accessDenied} denied`}
          />
        </div>
      ) : null}
    </div>
  )
}

export function MembershipReportPage() {
  const rows = useQuery({
    queryKey: ['reports', 'memberships'],
    queryFn: () => api<Record<string, unknown>[]>('/api/v1/reports/memberships'),
  })
  return (
    <div>
      <PageHeader title="Membership report" description="Current membership rows from the reporting API." />
      {rows.isLoading ? <Skeleton className="h-40" /> : null}
      {rows.error ? <QueryError error={rows.error} onRetry={() => void rows.refetch()} /> : null}
      {rows.data && rows.data.length === 0 ? (
        <EmptyState title="No memberships" body="Memberships appear here once members are enrolled on a plan." />
      ) : null}
      {rows.data && rows.data.length > 0 ? (
        <TableShell>
          <Table>
            <THead>
              <tr>
                <Th>Plan</Th>
                <Th>Status</Th>
                <Th>Dates</Th>
                <Th>Paid</Th>
              </tr>
            </THead>
            <tbody>
              {rows.data.map((row) => (
                <Tr key={String(row.id)}>
                  <Td className="font-medium">{String(row.planName)}</Td>
                  <Td>
                    <Badge tone={statusTone(String(row.status))}>{String(row.status)}</Badge>
                  </Td>
                  <Td className="text-muted">
                    {String(row.startDate)} → {String(row.endDate)}
                  </Td>
                  <Td className="tabular-nums">{String(row.amountPaid)}</Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        </TableShell>
      ) : null}
    </div>
  )
}

export function DeviceReportPage() {
  const summary = useQuery({
    queryKey: ['reports', 'summary'],
    queryFn: () => api<Summary>('/api/v1/reports/summary'),
  })
  return (
    <div>
      <PageHeader title="Device report" description="Connection state at query time — not a live status lamp." />
      {summary.isLoading ? <Skeleton className="h-32" /> : null}
      {summary.error ? <QueryError error={summary.error} onRetry={() => void summary.refetch()} /> : null}
      {summary.data?.devices?.length === 0 ? (
        <EmptyState title="No devices" body="Register a gateway and TrueFace terminal to see health here." />
      ) : null}
      {summary.data?.devices?.length ? (
        <div className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-panel shadow-[var(--shadow-panel)]">
          {summary.data.devices.map((d) => (
            <Link
              key={d.id}
              to={`/app/devices/${d.id}`}
              className="flex items-center justify-between gap-3 px-4 py-3.5 text-sm transition hover:bg-raised/50"
            >
              <span className="font-medium">{d.name}</span>
              <Badge tone={statusTone(d.connectionState)}>{d.connectionState}</Badge>
            </Link>
          ))}
        </div>
      ) : null}
    </div>
  )
}
