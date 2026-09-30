import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { PageNav } from '@/components/Pager'
import { QueryError } from '@/components/QueryError'
import {
  Badge,
  EmptyState,
  PageHeader,
  Select,
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
import type { PageResponse } from '@/lib/types'

type Audit = {
  id: string
  action: string
  result: string
  resourceType: string | null
  resourceId: string | null
  resourceLabel: string | null
  actorUsername: string | null
  correlationId: string | null
  details: string | null
  createdAt: string
}

const PAGE_SIZES = [10, 25, 50, 100] as const

function detailText(details: string | null) {
  if (!details) return null
  try {
    const value = JSON.parse(details) as Record<string, unknown>
    const parts = Object.entries(value).map(([key, item]) => `${key}: ${String(item)}`)
    return parts.length > 0 ? parts.join(' · ') : details
  } catch {
    return details
  }
}

export function AuditPage() {
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState<(typeof PAGE_SIZES)[number]>(25)
  const logs = useQuery({
    queryKey: ['audit', page, pageSize],
    queryFn: () => api<PageResponse<Audit>>(`/api/v1/audit-logs?page=${page}&size=${pageSize}`),
  })

  return (
    <div>
      <PageHeader
        title="Audit"
        description="Security and business actions recorded by the API, newest first."
      />
      {logs.isLoading ? <Skeleton className="h-32" /> : null}
      {logs.error ? <QueryError error={logs.error} onRetry={() => void logs.refetch()} /> : null}
      {logs.data && logs.data.content.length === 0 ? (
        <EmptyState title="No audit events" body="Actions such as login failures and privileged changes will appear here." />
      ) : null}
      {logs.data && logs.data.content.length > 0 ? (
        <>
          <TableShell>
            <Table className="min-w-[880px]">
              <THead>
                <tr>
                  <Th>When</Th>
                  <Th>Actor</Th>
                  <Th>Action</Th>
                  <Th>Resource</Th>
                  <Th>Details</Th>
                </tr>
              </THead>
              <tbody>
                {logs.data.content.map((row) => {
                  const details = detailText(row.details)
                  return (
                    <Tr key={row.id}>
                      <Td className="whitespace-nowrap text-muted">
                        <div>{formatDateTime(row.createdAt)}</div>
                        {row.correlationId ? (
                          <div className="mt-0.5 font-mono text-[11px]" title="Matches requestId in the API logs">
                            {row.correlationId}
                          </div>
                        ) : null}
                      </Td>
                      <Td className="font-medium">{row.actorUsername ?? '—'}</Td>
                      <Td>
                        <span className="font-medium">{row.action}</span>
                        <span className="mx-1.5 text-muted">·</span>
                        <Badge tone={statusTone(row.result)}>{row.result}</Badge>
                      </Td>
                      <Td>
                        <div className="font-medium text-ink">
                          {row.resourceType}
                          {row.resourceLabel ? ` · ${row.resourceLabel}` : ''}
                        </div>
                        {row.resourceId ? (
                          <div className="mt-0.5 font-mono text-[11px] text-muted" title={row.resourceId}>
                            {row.resourceId}
                          </div>
                        ) : null}
                      </Td>
                      <Td className="max-w-sm text-xs text-muted">{details ?? '—'}</Td>
                    </Tr>
                  )
                })}
              </tbody>
            </Table>
          </TableShell>
          <PageNav
            data={logs.data}
            onPageChange={setPage}
            extra={
              <label className="flex items-center gap-2 text-xs text-muted">
                Show
                <Select
                  aria-label="Rows per page"
                  className="w-auto py-1.5 pr-8 text-xs"
                  value={pageSize}
                  onChange={(e) => {
                    const next = Number(e.target.value)
                    if (next === 10 || next === 25 || next === 50 || next === 100) {
                      setPageSize(next)
                      setPage(0)
                    }
                  }}
                >
                  {PAGE_SIZES.map((size) => (
                    <option key={size} value={size}>
                      {size}
                    </option>
                  ))}
                </Select>
              </label>
            }
          />
        </>
      ) : null}
    </div>
  )
}
