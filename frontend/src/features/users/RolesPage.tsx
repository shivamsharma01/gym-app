import { useQuery } from '@tanstack/react-query'
import { PageHeader, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'

type Role = { id: string; name: string; description: string | null; system: boolean; permissions: string[] }

export function RolesPage() {
  const roles = useQuery({
    queryKey: ['roles'],
    queryFn: () => api<Role[]>('/api/v1/roles'),
  })
  return (
    <div>
      <PageHeader title="Roles" description="System roles are read-only in this phase." />
      {roles.isLoading ? <Skeleton className="h-32" /> : null}
      {roles.error ? <QueryError error={roles.error} /> : null}
      <div className="space-y-3">
        {roles.data?.map((r) => (
          <article key={r.id} className="rounded-xl border border-line p-4">
            <div className="font-semibold">{r.name}</div>
            <p className="text-sm text-muted">{r.description}</p>
            <p className="mt-2 text-xs text-muted">{r.permissions.join(', ')}</p>
          </article>
        ))}
      </div>
    </div>
  )
}
