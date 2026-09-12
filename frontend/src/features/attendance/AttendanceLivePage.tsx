import { useQuery } from '@tanstack/react-query'
import { Badge, Button, PageHeader, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
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
          <Button variant="outline" onClick={() => void attendance.refetch()} disabled={attendance.isFetching}>
            Refresh now
          </Button>
        }
      />
      {attendance.isLoading ? <Skeleton className="h-40" /> : null}
      {attendance.error ? <QueryError error={attendance.error} /> : null}
      <ul className="divide-y divide-line rounded-xl border border-line bg-panel">
        {attendance.data?.content.map((row) => (
          <li key={row.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-sm">
            <div>
              <div className="font-medium">{row.deviceUserId ?? 'Unknown device user'}</div>
              <div className="text-xs text-muted">{formatDateTime(row.occurredAt)}</div>
            </div>
            <Badge tone={statusTone(row.result)}>
              {row.result} · {row.direction}
            </Badge>
          </li>
        ))}
      </ul>
      {attendance.data && attendance.data.content.length === 0 ? (
        <p className="mt-4 text-sm text-muted">Waiting for the gateway to report events.</p>
      ) : null}
    </div>
  )
}
