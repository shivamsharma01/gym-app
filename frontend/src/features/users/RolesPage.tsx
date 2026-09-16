import { useQuery } from '@tanstack/react-query'
import { QueryError } from '@/components/QueryError'
import { Badge, Card, EmptyState, PageHeader, Skeleton } from '@/components/ui'
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
      {roles.error ? <QueryError error={roles.error} onRetry={() => void roles.refetch()} /> : null}
      {roles.data && roles.data.length === 0 ? (
        <EmptyState title="No roles" body="Roles are seeded by the backend migrations." />
      ) : null}
      <div className="grid gap-3 lg:grid-cols-2">
        {roles.data?.map((r) => (
          <Card key={r.id} className="p-4">
            <div className="flex flex-wrap items-center gap-2">
              <div className="font-semibold tracking-tight">{r.name}</div>
              {r.system ? <Badge tone="muted">System</Badge> : null}
            </div>
            <p className="mt-1 text-sm text-muted">{r.description}</p>
            <p className="mt-3 text-xs leading-relaxed text-muted">{r.permissions.join(' · ')}</p>
          </Card>
        ))}
      </div>
    </div>
  )
}
