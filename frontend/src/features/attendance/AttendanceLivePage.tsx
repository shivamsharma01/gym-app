import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Activity } from 'lucide-react'
import { useState } from 'react'
import { PageNav } from '@/components/Pager'
import { QueryError } from '@/components/QueryError'
import { Badge, Button, Card, EmptyState, PageHeader, Skeleton } from '@/components/ui'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Attendance, PageResponse } from '@/lib/types'

export function AttendanceLivePage() {
  const [page, setPage] = useState(0)
  const attendance = useQuery({
    queryKey: ['attendance', 'live', page],
    queryFn: () => api<PageResponse<Attendance>>(`/api/v1/attendance?page=${page}&size=25`),
    refetchInterval: 60_000,
    placeholderData: keepPreviousData,
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
                <div className="font-medium">{row.memberName ?? 'Unknown member'}</div>
                <div className="mt-0.5 font-mono text-[11px] text-muted">{row.deviceUserId ?? '—'}</div>
                <div className="mt-0.5 text-xs text-muted">{formatDateTime(row.occurredAt)}</div>
              </div>
              <Badge tone={statusTone(row.result)}>
                {row.result} · {row.direction}
              </Badge>
            </div>
          ))}
        </Card>
      ) : null}
      {attendance.data && attendance.data.content.length > 0 ? (
        <PageNav data={attendance.data} onPageChange={setPage} />
      ) : null}
    </div>
  )
}
