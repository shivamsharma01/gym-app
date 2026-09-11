import { useQuery } from '@tanstack/react-query'
import { Card, PageHeader, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import type { UserSummary } from '@/lib/types'

export function ProfilePage() {
  const me = useQuery({
    queryKey: ['me'],
    queryFn: () => api<UserSummary>('/api/v1/me'),
  })

  return (
    <div className="max-w-xl">
      <PageHeader title="Profile" description="Loaded from /api/v1/me. Tokens stay in memory only." />
      {me.isLoading ? <Skeleton className="h-32" /> : null}
      {me.error ? <QueryError error={me.error} /> : null}
      {me.data ? (
        <Card className="space-y-3 text-sm">
          <div>
            <div className="text-xs uppercase text-muted">Name</div>
            {me.data.fullName}
          </div>
          <div>
            <div className="text-xs uppercase text-muted">Username</div>
            {me.data.username}
          </div>
          <div>
            <div className="text-xs uppercase text-muted">Email</div>
            {me.data.email}
          </div>
          <div>
            <div className="text-xs uppercase text-muted">Roles</div>
            {me.data.roles.join(', ')}
          </div>
          <div>
            <div className="text-xs uppercase text-muted">Permissions</div>
            <p className="mt-1 text-muted">{me.data.permissions.join(', ')}</p>
          </div>
        </Card>
      ) : null}
    </div>
  )
}
