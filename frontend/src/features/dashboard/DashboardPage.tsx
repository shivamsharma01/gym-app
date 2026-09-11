import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Card, PageHeader, Skeleton } from '@/components/ui'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import type { Attendance, Device, Member, PageResponse, Payment } from '@/lib/types'

export function DashboardPage() {
  const { user, has } = useAuth()
  const members = useQuery({
    queryKey: ['members', 'count'],
    queryFn: () => api<PageResponse<Member>>('/api/v1/members?page=0&size=1'),
    enabled: has('MEMBER_VIEW'),
  })
  const devices = useQuery({
    queryKey: ['devices', 'count'],
    queryFn: () => api<PageResponse<Device>>('/api/v1/devices?page=0&size=1'),
    enabled: has('DEVICE_VIEW'),
  })
  const attendance = useQuery({
    queryKey: ['attendance', 'recent'],
    queryFn: () => api<PageResponse<Attendance>>('/api/v1/attendance?page=0&size=5'),
    enabled: has('ATTENDANCE_VIEW'),
  })
  const payments = useQuery({
    queryKey: ['payments', 'count'],
    queryFn: () => api<PageResponse<Payment>>('/api/v1/payments?page=0&size=1'),
    enabled: has('PAYMENT_VIEW'),
  })

  return (
    <div>
      <PageHeader title="Dashboard" description={`Welcome back, ${user?.fullName ?? 'there'}.`} />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Members" to="/app/members" value={members.data?.totalElements} loading={members.isLoading} />
        <Stat label="Devices" to="/app/devices" value={devices.data?.totalElements} loading={devices.isLoading} />
        <Stat label="Payments" to="/app/payments" value={payments.data?.totalElements} loading={payments.isLoading} />
        <Stat label="Attendance rows" to="/app/attendance" value={attendance.data?.totalElements} loading={attendance.isLoading} />
      </div>
      <h2 className="mb-3 mt-10 text-sm font-semibold uppercase tracking-wide text-muted">Recent attendance</h2>
      {attendance.isLoading ? (
        <Skeleton className="h-32" />
      ) : !attendance.data?.content.length ? (
        <p className="text-sm text-muted">No attendance yet. Events appear when the device gateway reports them.</p>
      ) : (
        <ul className="divide-y divide-line rounded-xl border border-line bg-panel">
          {attendance.data.content.map((row) => (
            <li key={row.id} className="flex justify-between px-4 py-3 text-sm">
              <span>{row.deviceUserId ?? 'Unknown user'}</span>
              <span className="text-muted">{row.result} · {row.direction}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function Stat({
  label,
  value,
  to,
  loading,
}: {
  label: string
  value?: number
  to: string
  loading: boolean
}) {
  return (
    <Link to={to}>
      <Card className="transition hover:border-accent/40">
        <div className="text-xs font-semibold uppercase tracking-wide text-muted">{label}</div>
        <div className="mt-2 text-3xl font-extrabold">{loading ? '…' : (value ?? '—')}</div>
      </Card>
    </Link>
  )
}
