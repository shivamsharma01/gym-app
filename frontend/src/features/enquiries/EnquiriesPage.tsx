import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge, EmptyState, PageHeader, Select, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
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
      {list.error ? <QueryError error={list.error} /> : null}
      {list.data && list.data.content.length === 0 ? (
        <EmptyState title="No enquiries" body="When someone submits the public form, it lands here." />
      ) : null}
      <div className="space-y-3">
        {list.data?.content.map((e) => (
          <article key={e.id} className="rounded-xl border border-line bg-panel p-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="font-semibold">{e.name}</div>
              <Badge tone={statusTone(e.status)}>{e.status}</Badge>
            </div>
            <p className="mt-1 text-sm text-muted">
              {e.email} · {e.phone ?? 'no phone'} · {formatDateTime(e.createdAt)}
            </p>
            <p className="mt-3 text-sm">{e.message}</p>
            {has('ENQUIRY_MANAGE') ? (
              <Select
                className="mt-3 max-w-xs"
                value={e.status}
                onChange={(ev) => update.mutate({ id: e.id, status: ev.target.value })}
              >
                <option value="NEW">NEW</option>
                <option value="CONTACTED">CONTACTED</option>
                <option value="CLOSED">CLOSED</option>
              </Select>
            ) : null}
          </article>
        ))}
      </div>
    </div>
  )
}
