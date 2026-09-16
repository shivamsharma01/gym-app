import { useQuery } from '@tanstack/react-query'
import { QueryError } from '@/components/QueryError'
import { Badge, Card, PageHeader, Skeleton } from '@/components/ui'
import { api } from '@/lib/api'
import type { UserSummary } from '@/lib/types'

export function ProfilePage() {
  const me = useQuery({
    queryKey: ['me'],
    queryFn: () => api<UserSummary>('/api/v1/me'),
  })

  return (
    <div className="max-w-xl">
      <PageHeader title="Profile" description="Loaded from /api/v1/me. Access tokens stay in memory only." />
      {me.isLoading ? <Skeleton className="h-32" /> : null}
      {me.error ? <QueryError error={me.error} onRetry={() => void me.refetch()} /> : null}
      {me.data ? (
        <Card className="space-y-5">
          <ProfileRow label="Name" value={me.data.fullName} />
          <ProfileRow label="Username" value={me.data.username} />
          <ProfileRow label="Email" value={me.data.email} />
          <div>
            <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">Roles</div>
            <div className="mt-2 flex flex-wrap gap-1.5">
              {me.data.roles.map((r) => (
                <Badge key={r} tone="accent">
                  {r}
                </Badge>
              ))}
            </div>
          </div>
          <div>
            <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">Permissions</div>
            <p className="mt-2 text-sm leading-relaxed text-muted">{me.data.permissions.join(' · ')}</p>
          </div>
        </Card>
      ) : null}
    </div>
  )
}

function ProfileRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">{label}</div>
      <div className="mt-1 text-sm font-medium">{value}</div>
    </div>
  )
}
