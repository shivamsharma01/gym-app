import { useQuery } from '@tanstack/react-query'
import { Activity } from 'lucide-react'
import { Link } from 'react-router'
import { useState } from 'react'
import { QueryError } from '@/components/QueryError'
import {
  Badge,
  Button,
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
        description="Door events from the device gateway. Open Live view for near-real-time updates over the staff WebSocket."
        actions={
          <Link to="/app/attendance/live">
            <Button variant="outline">Live view</Button>
          </Link>
        }
      />
      {attendance.isLoading ? <Skeleton className="h-40" /> : null}
      {attendance.error ? <QueryError error={attendance.error} onRetry={() => void attendance.refetch()} /> : null}
      {attendance.data && attendance.data.content.length === 0 ? (
        <EmptyState
          title="No attendance yet"
          body="Rows appear after the gateway reports access events or a reconcile."
          icon={<Activity className="h-5 w-5" />}
        />
      ) : null}
      {attendance.data && attendance.data.content.length > 0 ? (
        <TableShell>
          <Table className="min-w-[720px]">
            <THead>
              <tr>
                <Th>When</Th>
                <Th>Device user</Th>
                <Th>Direction</Th>
                <Th>Result</Th>
                <Th>Member</Th>
              </tr>
            </THead>
            <tbody>
              {attendance.data.content.map((row) => (
                <Tr key={row.id}>
                  <Td className="whitespace-nowrap text-muted">{formatDateTime(row.occurredAt)}</Td>
                  <Td className="font-mono text-xs">{row.deviceUserId ?? '—'}</Td>
                  <Td className="text-muted">{row.direction}</Td>
                  <Td>
                    <Badge tone={statusTone(row.result)}>{row.result}</Badge>
                  </Td>
                  <Td className="text-muted">{row.memberLinked ? 'Linked' : 'Unlinked'}</Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        </TableShell>
      ) : null}
      {attendance.data && attendance.data.totalPages > 1 ? (
        <div className="mt-4 flex gap-2">
          <Button variant="outline" size="sm" disabled={attendance.data.first} onClick={() => setPage((n) => Math.max(0, n - 1))}>
            Previous
          </Button>
          <Button variant="outline" size="sm" disabled={attendance.data.last} onClick={() => setPage((n) => n + 1)}>
            Next
          </Button>
        </div>
      ) : null}
    </div>
  )
}
