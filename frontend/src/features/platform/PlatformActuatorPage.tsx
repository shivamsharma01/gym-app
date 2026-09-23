import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { QueryError } from '@/components/QueryError'
import { Button, PageHeader, Skeleton } from '@/components/ui'
import { apiUrl } from '@/lib/backendUrls'
import { getAccessToken } from '@/lib/tokens'
import { cn } from '@/lib/cn'

type ActuatorTab = 'health' | 'info' | 'metrics' | 'threaddump'

const TABS: { id: ActuatorTab; label: string; path: string; description: string }[] = [
  {
    id: 'health',
    label: 'Health',
    path: '/actuator/health',
    description: 'Application and database health (details when authorized).',
  },
  {
    id: 'info',
    label: 'Info',
    path: '/actuator/info',
    description: 'Build and application metadata.',
  },
  {
    id: 'metrics',
    label: 'Metrics',
    path: '/actuator/metrics',
    description: 'Micrometer metric names and selected series.',
  },
  {
    id: 'threaddump',
    label: 'Thread dump',
    path: '/actuator/threaddump',
    description: 'JVM thread dump for diagnosing stuck requests.',
  },
]

async function fetchActuator(path: string): Promise<{ contentType: string; body: string }> {
  const headers = new Headers()
  const token = getAccessToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const res = await fetch(apiUrl(path), { headers })
  const text = await res.text()
  if (!res.ok) {
    let detail = text
    try {
      const json = JSON.parse(text) as { detail?: string; title?: string }
      detail = json.detail || json.title || text
    } catch {
      /* keep text */
    }
    throw new Error(detail || `HTTP ${res.status}`)
  }
  return { contentType: res.headers.get('Content-Type') ?? '', body: text }
}

function prettyJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

export function PlatformActuatorPage() {
  const [tab, setTab] = useState<ActuatorTab>('health')
  const [metricName, setMetricName] = useState<string>('')
  const current = TABS.find((t) => t.id === tab)!

  const endpoint = useQuery({
    queryKey: ['actuator', tab],
    queryFn: () => fetchActuator(current.path),
  })

  const metricNames = useMemo(() => {
    if (tab !== 'metrics' || !endpoint.data) return [] as string[]
    try {
      const parsed = JSON.parse(endpoint.data.body) as { names?: string[] }
      return (parsed.names ?? []).slice().sort()
    } catch {
      return []
    }
  }, [tab, endpoint.data])

  const metricDetail = useQuery({
    queryKey: ['actuator', 'metric', metricName],
    queryFn: () => fetchActuator(`/actuator/metrics/${encodeURIComponent(metricName)}`),
    enabled: tab === 'metrics' && Boolean(metricName),
  })

  return (
    <div className="space-y-6">
      <PageHeader
        title="Operations console"
        description="Spring Boot Actuator for platform SUPER_ADMIN. Sensitive endpoints (heapdump, env, configprops) stay blocked."
      />

      <div className="flex flex-wrap gap-2 border-b border-line pb-3">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            onClick={() => {
              setTab(t.id)
              setMetricName('')
            }}
            className={cn(
              'rounded-lg px-3 py-1.5 text-sm font-medium transition',
              tab === t.id ? 'bg-raised text-ink shadow-[inset_0_-2px_0_0_var(--color-accent)]' : 'text-muted hover:bg-raised hover:text-ink',
            )}
          >
            {t.label}
          </button>
        ))}
      </div>

      <p className="text-sm text-muted">{current.description}</p>
      <p className="font-mono text-xs text-muted">
        GET <span className="text-ink">{current.path}</span>
      </p>

      <div className="flex gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => void endpoint.refetch()}
          disabled={endpoint.isFetching}
        >
          Refresh
        </Button>
      </div>

      {endpoint.isLoading ? <Skeleton className="h-48" /> : null}
      {endpoint.error ? <QueryError error={endpoint.error} onRetry={() => void endpoint.refetch()} /> : null}

      {tab === 'metrics' && metricNames.length > 0 ? (
        <div className="grid gap-4 lg:grid-cols-[minmax(12rem,18rem)_minmax(0,1fr)]">
          <div className="max-h-[28rem] overflow-y-auto rounded-xl border border-line bg-panel p-2">
            <ul className="space-y-0.5">
              {metricNames.map((name) => (
                <li key={name}>
                  <button
                    type="button"
                    className={cn(
                      'w-full rounded-md px-2 py-1.5 text-left font-mono text-[11px] text-muted hover:bg-raised hover:text-ink',
                      metricName === name && 'bg-raised text-ink',
                    )}
                    onClick={() => setMetricName(name)}
                  >
                    {name}
                  </button>
                </li>
              ))}
            </ul>
          </div>
          <div>
            {!metricName ? (
              <p className="text-sm text-muted">Select a metric name to load values.</p>
            ) : metricDetail.isLoading ? (
              <Skeleton className="h-40" />
            ) : metricDetail.error ? (
              <QueryError error={metricDetail.error} onRetry={() => void metricDetail.refetch()} />
            ) : (
              <pre className="max-h-[28rem] overflow-auto rounded-xl border border-line bg-canvas p-4 text-xs leading-relaxed text-ink">
                {prettyJson(metricDetail.data?.body ?? '')}
              </pre>
            )}
          </div>
        </div>
      ) : null}

      {tab !== 'metrics' && endpoint.data ? (
        <pre className="max-h-[36rem] overflow-auto rounded-xl border border-line bg-canvas p-4 text-xs leading-relaxed text-ink whitespace-pre-wrap">
          {tab === 'threaddump' ? endpoint.data.body : prettyJson(endpoint.data.body)}
        </pre>
      ) : null}

      {tab === 'metrics' && endpoint.data && metricNames.length === 0 && !endpoint.isLoading ? (
        <pre className="overflow-auto rounded-xl border border-line bg-canvas p-4 text-xs">{prettyJson(endpoint.data.body)}</pre>
      ) : null}
    </div>
  )
}
