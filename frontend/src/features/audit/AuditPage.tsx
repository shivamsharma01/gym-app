import { useQuery } from '@tanstack/react-query'
import { QueryError } from '@/components/QueryError'
import { Badge, EmptyState, PageHeader, Skeleton, Table, TableShell, THead, Th, Td, Tr } from '@/components/ui'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/cn'
import { statusTone } from '@/lib/status'
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
      <PageHeader title="Audit" description="Security-relevant actions recorded by the API." />
      {logs.isLoading ? <Skeleton className="h-32" /> : null}
      {logs.error ? <QueryError error={logs.error} onRetry={() => void logs.refetch()} /> : null}
      {logs.data && logs.data.content.length === 0 ? (
        <EmptyState title="No audit events" body="Actions such as login failures and privileged changes will appear here." />
      ) : null}
      {logs.data && logs.data.content.length > 0 ? (
        <TableShell>
          <Table className="min-w-[720px]">
            <THead>
              <tr>
                <Th>When</Th>
                <Th>Actor</Th>
                <Th>Action</Th>
                <Th>Resource</Th>
              </tr>
            </THead>
            <tbody>
              {logs.data.content.map((row) => (
                <Tr key={row.id}>
                  <Td className="whitespace-nowrap text-muted">{formatDateTime(row.createdAt)}</Td>
                  <Td className="font-medium">{row.actorUsername ?? '—'}</Td>
                  <Td>
                    <span className="font-medium">{row.action}</span>
                    <span className="mx-1.5 text-muted">·</span>
                    <Badge tone={statusTone(row.result)}>{row.result}</Badge>
                  </Td>
                  <Td className="text-muted">
                    {row.resourceType} {row.resourceId}
                  </Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        </TableShell>
      ) : null}
    </div>
  )
}
