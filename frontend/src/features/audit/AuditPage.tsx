import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { PageNav } from '@/components/Pager'
import { QueryError } from '@/components/QueryError'
import {
  Badge,
  Button,
  EmptyState,
  Input,
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

type AuditOptions = {
  actors: string[]
  actions: string[]
}

function auditPath(page: number, pageSize: number, actors: string[], actions: string[], from: string, to: string) {
  const params = new URLSearchParams({ page: String(page), size: String(pageSize) })
  for (const actor of actors) params.append('actor', actor)
  for (const action of actions) params.append('action', action)
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  return `/api/v1/audit-logs?${params.toString()}`
}

function MultiFilter({
  label,
  options,
  selected,
  onChange,
}: Readonly<{
  label: string
  options: string[]
  selected: string[]
  onChange: (next: string[]) => void
}>) {
  const [open, setOpen] = useState(false)
  const summary = selected.length === 0 ? label : `${label} (${selected.length})`
  return (
    <div className="relative">
      <Button type="button" variant="outline" size="sm" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
        {summary}
      </Button>
      {open ? (
        <div className="absolute z-20 mt-1 max-h-64 w-64 overflow-auto rounded-lg border border-line bg-canvas p-1 shadow-lg">
          {options.length === 0 ? (
            <p className="px-3 py-2 text-sm text-muted">Nothing to choose yet</p>
          ) : (
            options.map((option) => {
              const checked = selected.includes(option)
              return (
                <label key={option} className="flex cursor-pointer items-center gap-2 rounded-md px-3 py-1.5 text-sm hover:bg-raised">
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() =>
                      onChange(checked ? selected.filter((value) => value !== option) : [...selected, option])
                    }
                  />
                  <span className="truncate">{option}</span>
                </label>
              )
            })
          )}
        </div>
      ) : null}
    </div>
  )
}

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
  const [actors, setActors] = useState<string[]>([])
  const [actions, setActions] = useState<string[]>([])
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const options = useQuery({
    queryKey: ['audit-options'],
    queryFn: () => api<AuditOptions>('/api/v1/audit-logs/options'),
  })
  const logs = useQuery({
    queryKey: ['audit', page, pageSize, actors, actions, from, to],
    queryFn: () => api<PageResponse<Audit>>(auditPath(page, pageSize, actors, actions, from, to)),
  })
  const filtering = actors.length > 0 || actions.length > 0 || from !== '' || to !== ''

  return (
    <div>
      <PageHeader
        title="Audit"
        description="Who did what, newest first."
      />
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <MultiFilter
          label="Actor"
          options={options.data?.actors ?? []}
          selected={actors}
          onChange={(next) => {
            setActors(next)
            setPage(0)
          }}
        />
        <MultiFilter
          label="Action"
          options={options.data?.actions ?? []}
          selected={actions}
          onChange={(next) => {
            setActions(next)
            setPage(0)
          }}
        />
        <Input
          type="date"
          aria-label="From"
          value={from}
          onChange={(event) => {
            setFrom(event.target.value)
            setPage(0)
          }}
          className="w-40"
        />
        <Input
          type="date"
          aria-label="To"
          value={to}
          onChange={(event) => {
            setTo(event.target.value)
            setPage(0)
          }}
          className="w-40"
        />
        {filtering ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => {
              setActors([])
              setActions([])
              setFrom('')
              setTo('')
              setPage(0)
            }}
          >
            Clear filters
          </Button>
        ) : null}
      </div>
      {logs.isLoading ? <Skeleton className="h-32" /> : null}
      {logs.error ? <QueryError error={logs.error} onRetry={() => void logs.refetch()} /> : null}
      {logs.data && logs.data.content.length === 0 ? (
        <EmptyState
          title={filtering ? 'No matching events' : 'No audit events'}
          body={filtering ? 'Try another actor, action, or date.' : 'Logins and changes will appear here.'}
        />
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
