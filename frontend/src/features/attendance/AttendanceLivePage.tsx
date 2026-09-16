import { useQuery } from '@tanstack/react-query'
import { Activity } from 'lucide-react'
import { QueryError } from '@/components/QueryError'
import { Badge, Button, Card, EmptyState, PageHeader, Skeleton } from '@/components/ui'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Attendance, PageResponse } from '@/lib/types'

export function AttendanceLivePage() {
  const attendance = useQuery({
    queryKey: ['attendance', 'live'],
    queryFn: () => api<PageResponse<Attendance>>('/api/v1/attendance?page=0&size=25'),
    refetchInterval: 60_000,
  })

  return (
    <div>
      <PageHeader
        title="Attendance live"
        description="Pushes over /live when the staff socket is connected. Manual refresh still works if the socket is down."
        actions={
          <Button variant="outline" size="sm" onClick={() => void attendance.refetch()} disabled={attendance.isFetching}>
            Refresh now
          </Button>
        }
      />
      {attendance.isLoading ? <Skeleton className="h-40" /> : null}
      {attendance.error ? <QueryError error={attendance.error} onRetry={() => void attendance.refetch()} /> : null}
      {attendance.data && attendance.data.content.length === 0 ? (
        <EmptyState
          title="Waiting for events"
          body="Door activity appears here when the gateway reports access events."
          icon={<Activity className="h-5 w-5" />}
        />
      ) : null}
      {attendance.data && attendance.data.content.length > 0 ? (
        <Card padded={false} className="divide-y divide-line overflow-hidden">
          {attendance.data.content.map((row) => (
            <div key={row.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3.5 text-sm">
              <div>
                <div className="font-medium">{row.deviceUserId ?? 'Unknown device user'}</div>
                <div className="mt-0.5 text-xs text-muted">{formatDateTime(row.occurredAt)}</div>
              </div>
              <Badge tone={statusTone(row.result)}>
                {row.result} · {row.direction}
              </Badge>
            </div>
          ))}
        </Card>
      ) : null}
    </div>
  )
}
