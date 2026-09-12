import { useQuery } from '@tanstack/react-query'
import { PageHeader, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/cn'
import type { PageResponse } from '@/lib/types'

type Audit = {
  id: string
  action: string
  result: string
  resourceType: string | null
  resourceId: string | null
  actorUsername: string | null
  createdAt: string
}

export function AuditPage() {
  const logs = useQuery({
    queryKey: ['audit'],
    queryFn: () => api<PageResponse<Audit>>('/api/v1/audit-logs?size=50'),
  })
  return (
    <div>
      <PageHeader title="Audit" />
      {logs.isLoading ? <Skeleton className="h-32" /> : null}
      {logs.error ? <QueryError error={logs.error} /> : null}
      <div className="overflow-x-auto rounded-xl border border-line">
        <table className="w-full min-w-[720px] text-left text-sm">
          <thead className="bg-raised text-xs uppercase text-muted">
            <tr>
              <th className="px-4 py-3">When</th>
              <th className="px-4 py-3">Actor</th>
              <th className="px-4 py-3">Action</th>
              <th className="px-4 py-3">Resource</th>
            </tr>
          </thead>
          <tbody>
            {logs.data?.content.map((row) => (
              <tr key={row.id} className="border-t border-line">
                <td className="px-4 py-3">{formatDateTime(row.createdAt)}</td>
                <td className="px-4 py-3">{row.actorUsername ?? '—'}</td>
                <td className="px-4 py-3">
                  {row.action} · {row.result}
                </td>
                <td className="px-4 py-3 text-muted">
                  {row.resourceType} {row.resourceId}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
