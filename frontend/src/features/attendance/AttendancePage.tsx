import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { useState } from 'react'
import { Badge, Button, EmptyState, PageHeader, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Attendance, PageResponse } from '@/lib/types'

export function AttendancePage() {
  const [page, setPage] = useState(0)
  const attendance = useQuery({
    queryKey: ['attendance', page],
    queryFn: () => api<PageResponse<Attendance>>(`/api/v1/attendance?page=${page}&size=30`),
  })

  return (
    <div>
      <PageHeader
        title="Attendance"
        description="Events reported by the device gateway. Live WebSocket updates are not in this phase."
        actions={
          <Link to="/app/attendance/live">
            <Button variant="outline">Live view</Button>
          </Link>
        }
      />
      {attendance.isLoading ? <Skeleton className="h-40" /> : null}
      {attendance.error ? <QueryError error={attendance.error} /> : null}
      {attendance.data && attendance.data.content.length === 0 ? (
        <EmptyState title="No attendance yet" body="Rows appear after the gateway reports access events or a reconcile." />
      ) : null}
      {attendance.data && attendance.data.content.length > 0 ? (
        <div className="overflow-x-auto rounded-xl border border-line">
          <table className="w-full min-w-[720px] text-left text-sm">
            <thead className="bg-raised text-xs uppercase tracking-wide text-muted">
              <tr>
                <th className="px-4 py-3">When</th>
                <th className="px-4 py-3">Device user</th>
                <th className="px-4 py-3">Direction</th>
                <th className="px-4 py-3">Result</th>
                <th className="px-4 py-3">Member</th>
              </tr>
            </thead>
            <tbody>
              {attendance.data.content.map((row) => (
                <tr key={row.id} className="border-t border-line">
                  <td className="px-4 py-3">{formatDateTime(row.occurredAt)}</td>
                  <td className="px-4 py-3 font-mono text-xs">{row.deviceUserId ?? '—'}</td>
                  <td className="px-4 py-3 text-muted">{row.direction}</td>
                  <td className="px-4 py-3">
                    <Badge tone={statusTone(row.result)}>{row.result}</Badge>
                  </td>
                  <td className="px-4 py-3 text-muted">{row.memberLinked ? 'Linked' : 'Unlinked'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {attendance.data && attendance.data.totalPages > 1 ? (
        <div className="mt-4 flex gap-2">
          <Button variant="outline" disabled={attendance.data.first} onClick={() => setPage((n) => Math.max(0, n - 1))}>
            Previous
          </Button>
          <Button variant="outline" disabled={attendance.data.last} onClick={() => setPage((n) => n + 1)}>
            Next
          </Button>
        </div>
      ) : null}
    </div>
  )
}
