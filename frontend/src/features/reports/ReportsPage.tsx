import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Card, PageHeader, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { money } from '@/lib/cn'

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
        description="Server-side counts for the last 30 days unless you change the API range."
      />
      {summary.isLoading ? <Skeleton className="h-32" /> : null}
      {summary.error ? <QueryError error={summary.error} /> : null}
      {summary.data ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Stat label="Members" value={summary.data.membersTotal} hint={`${summary.data.membersActive} active`} />
          <Stat label="Active memberships" value={summary.data.membershipsActive} hint={`${summary.data.membershipsExpiringSoon} expiring in 7 days`} />
          <Stat
            label="Payments"
            value={money(summary.data.paymentsCompletedAmount, 'INR')}
            hint={`${summary.data.paymentsCompletedCount} completed`}
          />
          <Stat label="Attendance events" value={summary.data.attendanceEvents} hint={`${summary.data.accessDenied} denied`} />
        </div>
      ) : null}
      <div className="mt-8 flex flex-wrap gap-3 text-sm">
        <Link className="underline" to="/app/reports/memberships">
          Membership snapshot
        </Link>
        <Link className="underline" to="/app/reports/devices">
          Device health
        </Link>
      </div>
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
      <PageHeader title="Membership report" />
      {rows.error ? <QueryError error={rows.error} /> : null}
      <div className="overflow-x-auto rounded-xl border border-line">
        <table className="w-full min-w-[640px] text-left text-sm">
          <thead className="bg-raised text-xs uppercase text-muted">
            <tr>
              <th className="px-4 py-3">Plan</th>
              <th className="px-4 py-3">Status</th>
              <th className="px-4 py-3">Dates</th>
              <th className="px-4 py-3">Paid</th>
            </tr>
          </thead>
          <tbody>
            {(rows.data ?? []).map((row) => (
              <tr key={String(row.id)} className="border-t border-line">
                <td className="px-4 py-3">{String(row.planName)}</td>
                <td className="px-4 py-3">{String(row.status)}</td>
                <td className="px-4 py-3 text-muted">
                  {String(row.startDate)} → {String(row.endDate)}
                </td>
                <td className="px-4 py-3">{String(row.amountPaid)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
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
      <PageHeader title="Device report" description="Connection state at query time — not a live lamp." />
      <ul className="divide-y divide-line rounded-xl border border-line">
        {(summary.data?.devices ?? []).map((d) => (
          <li key={d.id} className="flex justify-between px-4 py-3 text-sm">
            <span>{d.name}</span>
            <span className="text-muted">{d.connectionState}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function Stat({ label, value, hint }: { label: string; value: string | number; hint: string }) {
  return (
    <Card>
      <div className="text-xs uppercase text-muted">{label}</div>
      <div className="mt-2 text-3xl font-extrabold">{value}</div>
      <div className="mt-1 text-xs text-muted">{hint}</div>
    </Card>
  )
}
