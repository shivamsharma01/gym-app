import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { Badge, Button, Card, EmptyState, Input, Label, PageHeader, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { formatDateTime } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Device, Gateway, GatewayCreated, PageResponse } from '@/lib/types'

export function DevicesPage() {
  const { has } = useAuth()
  const qc = useQueryClient()
  const devices = useQuery({
    queryKey: ['devices'],
    queryFn: () => api<PageResponse<Device>>('/api/v1/devices?size=50'),
  })
  const gateways = useQuery({
    queryKey: ['gateways'],
    queryFn: () => api<PageResponse<Gateway>>('/api/v1/gateways?size=50'),
  })
  const [gwName, setGwName] = useState('')
  const [issued, setIssued] = useState<GatewayCreated | null>(null)
  const createGateway = useMutation({
    mutationFn: () => api<GatewayCreated>('/api/v1/gateways', { method: 'POST', body: JSON.stringify({ name: gwName }) }),
    onSuccess: (created) => {
      setIssued(created)
      setGwName('')
      void qc.invalidateQueries({ queryKey: ['gateways'] })
    },
  })

  return (
    <div className="space-y-10">
      <PageHeader
        title="Devices"
        description="TrueFace terminals and the Windows LAN gateway that talks to them."
        actions={
          has('DEVICE_MANAGE') ? (
            <Link to="/app/devices/new">
              <Button>Register device</Button>
            </Link>
          ) : null
        }
      />

      {devices.isLoading ? <Skeleton className="h-32" /> : null}
      {devices.error ? <QueryError error={devices.error} /> : null}
      {devices.data && devices.data.content.length === 0 ? (
        <EmptyState title="No devices" body="Create a gateway first, then register the TrueFace terminal against it." />
      ) : null}
      {devices.data && devices.data.content.length > 0 ? (
        <div className="overflow-x-auto rounded-xl border border-line">
          <table className="w-full min-w-[640px] text-left text-sm">
            <thead className="bg-raised text-xs uppercase tracking-wide text-muted">
              <tr>
                <th className="px-4 py-3">Name</th>
                <th className="px-4 py-3">Role</th>
                <th className="px-4 py-3">Host</th>
                <th className="px-4 py-3">Connection</th>
                <th className="px-4 py-3">Gateway</th>
              </tr>
            </thead>
            <tbody>
              {devices.data.content.map((d) => (
                <tr key={d.id} className="border-t border-line hover:bg-raised/60">
                  <td className="px-4 py-3">
                    <Link className="font-semibold hover:underline" to={`/app/devices/${d.id}`}>
                      {d.name}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-muted">{d.role}</td>
                  <td className="px-4 py-3 font-mono text-xs">{d.host ? `${d.host}:${d.port ?? ''}` : '—'}</td>
                  <td className="px-4 py-3">
                    <Badge tone={statusTone(d.connectionState)}>{d.connectionState}</Badge>
                  </td>
                  <td className="px-4 py-3 text-muted">{d.gatewayAssigned ? 'Assigned' : 'None'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      <section>
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-muted">Gateways</h2>
        {gateways.error ? <QueryError error={gateways.error} /> : null}
        <div className="grid gap-3 sm:grid-cols-2">
          {gateways.data?.content.map((g) => (
            <Card key={g.id}>
              <div className="font-semibold">{g.name}</div>
              <p className="mt-1 text-sm text-muted">
                {g.status} · last heartbeat {formatDateTime(g.lastHeartbeatAt)}
              </p>
              <p className="mt-2 font-mono text-xs text-muted">id {g.id}</p>
            </Card>
          ))}
        </div>
        {has('DEVICE_MANAGE') ? (
          <Card className="mt-4 max-w-lg space-y-3">
            <h3 className="text-sm font-semibold">Create gateway</h3>
            <p className="text-sm text-muted">
              The plaintext token is shown once. Put it on the Windows agent as GYM_GATEWAY_TOKEN. It is never stored
              in this browser and will not be returned again.
            </p>
            <Label>Name</Label>
            <Input value={gwName} onChange={(e) => setGwName(e.target.value)} placeholder="Front desk PC" />
            {createGateway.error ? <QueryError error={createGateway.error} /> : null}
            <Button disabled={!gwName.trim() || createGateway.isPending} onClick={() => createGateway.mutate()}>
              Issue token
            </Button>
          </Card>
        ) : null}
      </section>

      {issued ? (
        <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/70 px-4">
          <Card className="w-full max-w-lg space-y-4">
            <h2 className="text-lg font-extrabold">Gateway token (shown once)</h2>
            <p className="text-sm text-muted">
              Copy this into the Windows gateway config now. Closing this dialog means you cannot retrieve it from the
              app.
            </p>
            <p className="break-all rounded-md bg-raised p-3 font-mono text-xs">{issued.token}</p>
            <p className="font-mono text-xs text-muted">gateway id {issued.id}</p>
            <div className="flex gap-2">
              <Button
                type="button"
                onClick={() => {
                  void navigator.clipboard.writeText(issued.token)
                }}
              >
                Copy token
              </Button>
              <Button variant="outline" onClick={() => setIssued(null)}>
                I have saved it
              </Button>
            </div>
          </Card>
        </div>
      ) : null}
    </div>
  )
}
