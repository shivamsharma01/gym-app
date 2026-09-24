import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Inbox } from 'lucide-react'
import { QueryError } from '@/components/QueryError'
import { Badge, Card, EmptyState, PageHeader, Select, Skeleton } from '@/components/ui'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { formatDateTime } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { PageResponse } from '@/lib/types'

type Enquiry = {
  id: string
  name: string
  email: string
  phone: string | null
  message: string
  planInterest: string | null
  status: string
  staffNotes: string | null
  createdAt: string
}

export function EnquiriesPage() {
  const { has } = useAuth()
  const qc = useQueryClient()
  const list = useQuery({
    queryKey: ['enquiries'],
    queryFn: () => api<PageResponse<Enquiry>>('/api/v1/enquiries?size=50'),
  })
  const update = useMutation({
    mutationFn: ({ id, status }: { id: string; status: string }) =>
      api(`/api/v1/enquiries/${id}`, { method: 'PUT', body: JSON.stringify({ status }) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['enquiries'] }),
  })

  return (
    <div>
      <PageHeader title="Enquiries" description="Leads from the public contact form." />
      {list.isLoading ? <Skeleton className="h-32" /> : null}
      {list.error ? <QueryError error={list.error} onRetry={() => void list.refetch()} /> : null}
      {list.data && list.data.content.length === 0 ? (
        <EmptyState
          title="No enquiries"
          body="When someone submits the public form, it lands here."
          icon={<Inbox className="h-5 w-5" />}
        />
      ) : null}
      <div className="space-y-3">
        {list.data?.content.map((e) => (
          <Card key={e.id} className="p-4 sm:p-5">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div>
                <div className="font-semibold tracking-tight">{e.name}</div>
                <p className="mt-1 text-sm text-muted">
                  {e.email}
                  {e.phone ? ` · ${e.phone}` : ''} · {formatDateTime(e.createdAt)}
                </p>
              </div>
              <Badge tone={statusTone(e.status)}>{e.status}</Badge>
            </div>
            {e.planInterest ? <p className="mt-2 text-xs font-medium text-accent">Interest: {e.planInterest}</p> : null}
            <p className="mt-3 text-sm leading-relaxed text-ink/90">{e.message}</p>
            {has('ENQUIRY_MANAGE') ? (
              <Select
                className="mt-4 max-w-xs"
                value={e.status}
                aria-label={`Status for ${e.name}`}
                onChange={(ev) => update.mutate({ id: e.id, status: ev.target.value })}
              >
                <option value="NEW">NEW</option>
                <option value="CONTACTED">CONTACTED</option>
                <option value="CLOSED">CLOSED</option>
              </Select>
            ) : null}
          </Card>
        ))}
      </div>
    </div>
  )
}
