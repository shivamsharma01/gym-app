import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Activity } from 'lucide-react'
import { Link, useLocation, useSearchParams } from 'react-router'
import { PageNav } from '@/components/Pager'
import { QueryError } from '@/components/QueryError'
import {
  Badge,
  Button,
  EmptyState,
  PageHeader,
  Select,
  Skeleton,
  SortableTh,
  type SortDirection,
  Table,
  TableShell,
  THead,
  Th,
  Td,
  Toolbar,
  Tr,
} from '@/components/ui'
import { DeviceUserCell } from '@/features/attendance/DeviceUserCell'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Attendance, PageResponse } from '@/lib/types'

type AttendanceFilter = 'ALL' | 'GRANTED' | 'DENIED' | 'VALIDITY_ENDED'

export function AttendancePage() {
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const direction = (params.get('direction') === 'asc' ? 'asc' : 'desc') as SortDirection
  const filter = parseFilter(params.get('filter'))
  const listPath = `${location.pathname}${location.search}`

  const attendance = useQuery({
    queryKey: ['attendance', page, direction, filter],
    queryFn: () => {
      const q = new URLSearchParams({ page: String(page), size: '30', direction })
      if (filter !== 'ALL') q.set('filter', filter)
      return api<PageResponse<Attendance>>(`/api/v1/attendance?${q}`)
    },
    placeholderData: keepPreviousData,
    refetchInterval: 15_000,
  })

  const setPage = (next: number) => {
    setParams((current) => {
      const nextParams = new URLSearchParams(current)
      if (next <= 0) nextParams.delete('page')
      else nextParams.set('page', String(next))
      return nextParams
    }, { replace: true })
  }

  return (
    <div>
      <PageHeader
        title="Attendance"
        description="Door punches. This list refreshes every 15 seconds."
        actions={
          <Link to="/app/attendance/live">
            <Button variant="outline">Live view</Button>
          </Link>
        }
      />
      <Toolbar>
        <Select
          aria-label="Result filter"
          value={filter}
          onChange={(e) => {
            const value = parseFilter(e.target.value)
            setParams((current) => {
              const nextParams = new URLSearchParams(current)
              nextParams.delete('page')
              if (value === 'ALL') nextParams.delete('filter')
              else nextParams.set('filter', value)
              return nextParams
            }, { replace: true })
          }}
          className="w-56"
        >
          <option value="ALL">All results</option>
          <option value="GRANTED">Granted</option>
          <option value="DENIED">Denied</option>
          <option value="VALIDITY_ENDED">Validity ended</option>
        </Select>
      </Toolbar>
      {attendance.isLoading ? <Skeleton className="h-40" /> : null}
      {attendance.error ? <QueryError error={attendance.error} onRetry={() => void attendance.refetch()} /> : null}
      {attendance.data && attendance.data.content.length === 0 ? (
        <EmptyState
          title="No attendance yet"
          body={
            filter === 'ALL'
              ? 'Punches show up here a few seconds after someone uses a reader.'
              : 'No punches match this filter.'
          }
          icon={<Activity className="h-5 w-5" />}
        />
      ) : null}
      {attendance.data && attendance.data.content.length > 0 ? (
        <TableShell>
          <Table className="min-w-[720px]">
            <THead>
              <tr>
                <SortableTh
                  direction={direction}
                  onSort={() => {
                    setParams((current) => {
                      const nextParams = new URLSearchParams(current)
                      nextParams.delete('page')
                      nextParams.set('direction', direction === 'desc' ? 'asc' : 'desc')
                      return nextParams
                    }, { replace: true })
                  }}
                >
                  When
                </SortableTh>
                <Th>Device user</Th>
                <Th>
                  <div className="flex flex-col gap-0.5 text-[10px] font-semibold uppercase tracking-wide text-muted">
                    <span>Reader</span>
                    <span>Direction</span>
                  </div>
                </Th>
                <Th>Result</Th>
              </tr>
            </THead>
            <tbody>
              {attendance.data.content.map((row) => (
                <Tr key={row.id}>
                  <Td className="whitespace-nowrap text-muted">{formatDateTime(row.occurredAt)}</Td>
                  <Td>
                    <DeviceUserCell row={row} from={listPath} />
                  </Td>
                  <Td>
                    <div className="flex min-w-[9.5rem] flex-col gap-1 rounded-md border border-line px-2 py-1.5">
                      <div className="flex items-center gap-2">
                        <span className="w-14 shrink-0 text-[10px] uppercase tracking-wide text-muted">Reader</span>
                        <span className="font-medium">{row.reader ?? '—'}</span>
                      </div>
                      <div className="flex items-center gap-2">
                        <span className="w-14 shrink-0 text-[10px] uppercase tracking-wide text-muted">Direction</span>
                        <span className="text-muted">{directionLabel(row.direction)}</span>
                      </div>
                    </div>
                  </Td>
                  <Td>
                    <Badge tone={statusTone(row.result)}>{row.result}</Badge>
                  </Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        </TableShell>
      ) : null}
      {attendance.data && attendance.data.content.length > 0 ? (
        <PageNav data={attendance.data} onPageChange={setPage} />
      ) : null}
    </div>
  )
}

function parseFilter(value: string | null): AttendanceFilter {
  if (value === 'GRANTED' || value === 'DENIED' || value === 'VALIDITY_ENDED') return value
  return 'ALL'
}

function directionLabel(value: string) {
  if (value === 'IN') return 'In'
  if (value === 'OUT') return 'Out'
  return value === 'UNKNOWN' ? 'Unknown' : value
}
