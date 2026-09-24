import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import {
  Activity,
  CreditCard,
  MonitorSmartphone,
  UserPlus,
  Users,
  Wallet,
} from 'lucide-react'
import { QueryError } from '@/components/QueryError'
import {
  Badge,
  Button,
  Card,
  EmptyState,
  PageHeader,
  SectionTitle,
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
import { useAuth } from '@/lib/auth'
import { formatDateTime, money } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Attendance, PageResponse } from '@/lib/types'

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

export function DashboardPage() {
  const { user, has } = useAuth()
  const canReport = has('REPORT_VIEW')
  const summary = useQuery({
    queryKey: ['reports', 'summary'],
    queryFn: () => api<Summary>('/api/v1/reports/summary'),
    enabled: canReport,
  })
  const attendance = useQuery({
    queryKey: ['attendance', 'recent'],
    queryFn: () => api<PageResponse<Attendance>>('/api/v1/attendance?page=0&size=8'),
    enabled: has('ATTENDANCE_VIEW'),
  })

  const firstName = user?.fullName?.split(/\s+/)[0] ?? 'there'

  return (
    <div>
      <PageHeader
        eyebrow="Overview"
        title={`Welcome back, ${firstName}`}
        description="Operational snapshot for this gym. Counts come from live APIs — nothing is mocked."
        actions={
          <div className="flex flex-wrap gap-2">
            {has('MEMBER_CREATE') ? (
              <Link to="/app/members/new">
                <Button size="sm">
                  <UserPlus className="h-4 w-4" /> Add member
                </Button>
              </Link>
            ) : null}
            {has('PAYMENT_CREATE') ? (
              <Link to="/app/payments">
                <Button size="sm" variant="outline">
                  <Wallet className="h-4 w-4" /> Record payment
                </Button>
              </Link>
            ) : null}
            {has('ATTENDANCE_VIEW') ? (
              <Link to="/app/attendance/live">
                <Button size="sm" variant="ghost">
                  <Activity className="h-4 w-4" /> Live door
                </Button>
              </Link>
            ) : null}
          </div>
        }
      />

      {canReport ? (
        <>
          {summary.isLoading ? (
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              {Array.from({ length: 4 }).map((_, i) => (
                <Skeleton key={i} className="h-28" />
              ))}
            </div>
          ) : null}
          {summary.error ? <QueryError error={summary.error} onRetry={() => void summary.refetch()} /> : null}
          {summary.data ? (
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              <StatCard
                label="Members"
                value={summary.data.membersTotal}
                hint={`${summary.data.membersActive} active`}
                to={has('MEMBER_VIEW') ? '/app/members' : undefined}
              />
              <StatCard
                label="Active memberships"
                value={summary.data.membershipsActive}
                hint={`${summary.data.membershipsExpiringSoon} expiring in 7 days · ${summary.data.membershipsExpired} expired`}
                to={has('MEMBERSHIP_VIEW') ? '/app/memberships' : undefined}
              />
              <StatCard
                label="Payments completed"
                value={money(summary.data.paymentsCompletedAmount, 'INR')}
                hint={`${summary.data.paymentsCompletedCount} transactions (report window)`}
                to={has('PAYMENT_VIEW') ? '/app/payments' : undefined}
              />
              <StatCard
                label="Attendance events"
                value={summary.data.attendanceEvents}
                hint={`${summary.data.accessDenied} access denied`}
                to={has('ATTENDANCE_VIEW') ? '/app/attendance' : undefined}
              />
            </div>
          ) : null}
        </>
      ) : (
        <Card className="text-sm text-muted">
          Ask an admin for <span className="font-medium text-ink">REPORT_VIEW</span> to see membership and revenue KPIs.
          Attendance and member lists still work with your current permissions.
        </Card>
      )}

      <div className="mt-8 grid gap-6 xl:grid-cols-[minmax(0,1.4fr)_minmax(18rem,0.8fr)]">
        <div>
          <SectionTitle
            title="Recent attendance"
            description="Latest door events from the gateway"
            actions={
              has('ATTENDANCE_VIEW') ? (
                <Link to="/app/attendance" className="text-xs font-semibold text-accent hover:underline">
                  View all
                </Link>
              ) : null
            }
          />
          {attendance.isLoading ? <Skeleton className="h-48" /> : null}
          {attendance.error ? <QueryError error={attendance.error} onRetry={() => void attendance.refetch()} /> : null}
          {attendance.data && attendance.data.content.length === 0 ? (
            <EmptyState
              title="No attendance yet"
              body="Events appear when the Windows gateway reports door activity from TrueFace."
              icon={<Activity className="h-5 w-5" />}
              action={
                has('DEVICE_VIEW') ? (
                  <Link to="/app/devices">
                    <Button variant="outline" size="sm">
                      Check devices
                    </Button>
                  </Link>
                ) : undefined
              }
            />
          ) : null}
          {attendance.data && attendance.data.content.length > 0 ? (
            <TableShell>
              <Table className="min-w-[520px]">
                <THead>
                  <tr>
                    <Th>When</Th>
                    <Th>User</Th>
                    <Th>Result</Th>
                    <Th>Direction</Th>
                  </tr>
                </THead>
                <tbody>
                  {attendance.data.content.map((row) => (
                    <Tr key={row.id}>
                      <Td className="whitespace-nowrap text-muted">{formatDateTime(row.occurredAt)}</Td>
                      <Td className="font-medium">{row.deviceUserId ?? 'Unknown'}</Td>
                      <Td>
                        <Badge tone={statusTone(row.result)}>{row.result}</Badge>
                      </Td>
                      <Td className="text-muted">{row.direction}</Td>
                    </Tr>
                  ))}
                </tbody>
              </Table>
            </TableShell>
          ) : null}
        </div>

        <div className="space-y-6">
          <div>
            <SectionTitle title="Quick actions" />
            <div className="grid gap-2">
              <QuickLink to="/app/members" icon={Users} label="Browse members" show={has('MEMBER_VIEW')} />
              <QuickLink to="/app/plans" icon={CreditCard} label="Manage plans" show={has('MEMBERSHIP_VIEW')} />
              <QuickLink to="/app/devices" icon={MonitorSmartphone} label="Devices & gateways" show={has('DEVICE_VIEW')} />
              <QuickLink to="/app/reports" icon={Activity} label="Full reports" show={has('REPORT_VIEW')} />
            </div>
          </div>

          {canReport && summary.data?.devices?.length ? (
            <div>
              <SectionTitle title="Devices" description="Connection at last report query" />
              <Card padded={false} className="divide-y divide-line overflow-hidden">
                {summary.data.devices.slice(0, 5).map((d) => (
                  <Link
                    key={d.id}
                    to={`/app/devices/${d.id}`}
                    className="flex items-center justify-between gap-3 px-4 py-3 text-sm transition hover:bg-raised/60"
                  >
                    <span className="truncate font-medium">{d.name}</span>
                    <Badge tone={statusTone(d.connectionState)}>{d.connectionState}</Badge>
                  </Link>
                ))}
              </Card>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  )
}

function QuickLink({
  to,
  icon: Icon,
  label,
  show,
}: {
  to: string
  icon: typeof Users
  label: string
  show: boolean
}) {
  if (!show) return null
  return (
    <Link
      to={to}
      className="flex items-center gap-3 rounded-xl border border-line bg-panel px-3.5 py-3 text-sm font-medium transition hover:border-accent/30 hover:bg-raised/50"
    >
      <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-raised text-muted">
        <Icon className="h-4 w-4" />
      </span>
      {label}
    </Link>
  )
}
