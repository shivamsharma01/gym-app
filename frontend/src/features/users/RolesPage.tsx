import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Check, X } from 'lucide-react'
import { QueryError } from '@/components/QueryError'
import { EmptyState, PageHeader, Skeleton } from '@/components/ui'
import { api } from '@/lib/api'
import { STAFF_ROLES } from '@/lib/catalog'
import { cn } from '@/lib/cn'

type Role = { id: string; name: string; description: string | null; system: boolean; permissions: string[] }
type Permission = { name: string; description: string | null }

const GYM_ROLE_ORDER = STAFF_ROLES.map((r) => r.name)

function roleLabel(name: string) {
  return STAFF_ROLES.find((r) => r.name === name)?.label ?? name
}

export function RolesPage() {
  const roles = useQuery({
    queryKey: ['roles'],
    queryFn: () => api<Role[]>('/api/v1/roles'),
  })
  const permissions = useQuery({
    queryKey: ['permissions'],
    queryFn: () => api<Permission[]>('/api/v1/permissions'),
  })

  const gymRoles = useMemo(() => {
    const byName = new Map((roles.data ?? []).filter((r) => r.name !== 'SUPER_ADMIN').map((r) => [r.name, r]))
    const ordered = GYM_ROLE_ORDER.map((name) => byName.get(name)).filter(Boolean) as Role[]
    const extras = [...byName.values()].filter((r) => !GYM_ROLE_ORDER.includes(r.name as (typeof GYM_ROLE_ORDER)[number]))
    return [...ordered, ...extras.sort((a, b) => a.name.localeCompare(b.name))]
  }, [roles.data])

  const permissionRows = useMemo(() => {
    const fromApi = permissions.data ?? []
    if (fromApi.length > 0) {
      return [...fromApi].sort((a, b) => a.name.localeCompare(b.name))
    }
    const names = new Set<string>()
    for (const r of gymRoles) {
      for (const p of r.permissions) names.add(p)
    }
    return [...names].sort().map((name) => ({ name, description: null as string | null }))
  }, [permissions.data, gymRoles])

  const loading = roles.isLoading || permissions.isLoading
  const error = roles.error ?? permissions.error

  return (
    <div>
      <PageHeader
        title="Roles"
        description="Read-only comparison of gym roles and permissions. Super Admin is not shown."
      />
      {loading ? <Skeleton className="h-48" /> : null}
      {error ? (
        <QueryError
          error={error}
          onRetry={() => {
            void roles.refetch()
            void permissions.refetch()
          }}
        />
      ) : null}
      {!loading && !error && gymRoles.length === 0 ? (
        <EmptyState title="No roles" body="Roles are seeded by the backend migrations." />
      ) : null}
      {!loading && !error && gymRoles.length > 0 ? (
        <div className="overflow-x-auto rounded-xl border border-line bg-panel">
          <table className="min-w-full border-collapse text-left text-sm">
            <thead>
              <tr className="border-b border-line bg-raised/60">
                <th className="sticky left-0 z-10 bg-raised/95 px-3 py-3 font-semibold text-ink backdrop-blur">
                  Permission
                </th>
                {gymRoles.map((r) => (
                  <th key={r.id} className="whitespace-nowrap px-3 py-3 text-center font-semibold text-ink">
                    <div>{roleLabel(r.name)}</div>
                    <div className="mt-0.5 text-[10px] font-medium uppercase tracking-wide text-muted">{r.name}</div>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {permissionRows.map((p) => (
                <tr key={p.name} className="border-b border-line/80 last:border-0">
                  <td className="sticky left-0 z-10 bg-panel px-3 py-2.5 backdrop-blur">
                    <div className="font-mono text-xs font-medium text-ink">{p.name}</div>
                    {p.description ? <div className="mt-0.5 max-w-xs text-xs text-muted">{p.description}</div> : null}
                  </td>
                  {gymRoles.map((r) => {
                    const allowed = r.permissions.includes(p.name)
                    return (
                      <td key={r.id} className="px-3 py-2.5 text-center">
                        <span
                          className={cn(
                            'inline-flex h-7 w-7 items-center justify-center rounded-full',
                            allowed ? 'bg-ok/15 text-ok' : 'bg-danger/10 text-danger',
                          )}
                          title={allowed ? 'Allowed' : 'Not allowed'}
                          aria-label={allowed ? `${r.name} has ${p.name}` : `${r.name} lacks ${p.name}`}
                        >
                          {allowed ? <Check className="h-4 w-4" strokeWidth={2.5} /> : <X className="h-4 w-4" strokeWidth={2.5} />}
                        </span>
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  )
}
