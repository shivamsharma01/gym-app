import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { MonitorSmartphone } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { Dialog } from '@/components/Dialog'
import { QueryError } from '@/components/QueryError'
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Input,
  Label,
  PageHeader,
  SectionTitle,
  Skeleton,
  Table,
  TableShell,
  THead,
  Th,
  Td,
  Tr,
} from '@/components/ui'
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
  const reissueEnrollment = useMutation({
    mutationFn: (id: string) =>
      api<GatewayCreated>(`/api/v1/gateways/${id}/enrollment`, { method: 'POST' }),
    onSuccess: (created) => setIssued(created),
  })

  return (
    <div className="space-y-10">
      <PageHeader
        title="Devices"
        description="Register gateways and TrueFace terminals here. The Windows PC still runs the gateway agent with the one-time token."
        actions={
          has('DEVICE_MANAGE') ? (
            <Link to="/app/devices/new">
              <Button>Register device</Button>
            </Link>
          ) : null
        }
      />

      {devices.isLoading ? <Skeleton className="h-32" /> : null}
      {devices.error ? <QueryError error={devices.error} onRetry={() => void devices.refetch()} /> : null}
      {devices.data && devices.data.content.length === 0 ? (
        <EmptyState
          title="No devices"
          body="Create a gateway first, then register the TrueFace terminal against it."
          icon={<MonitorSmartphone className="h-5 w-5" />}
        />
      ) : null}
      {devices.data && devices.data.content.length > 0 ? (
        <TableShell>
          <Table>
            <THead>
              <tr>
                <Th>Name</Th>
                <Th>Role</Th>
                <Th>Host</Th>
                <Th>Connection</Th>
                <Th>Gateway</Th>
              </tr>
            </THead>
            <tbody>
              {devices.data.content.map((d) => (
                <Tr key={d.id}>
                  <Td>
                    <Link className="font-semibold hover:text-accent" to={`/app/devices/${d.id}`}>
                      {d.name}
                    </Link>
                  </Td>
                  <Td className="text-muted">{d.role}</Td>
                  <Td className="font-mono text-xs text-muted">{d.host ? `${d.host}:${d.port ?? ''}` : '—'}</Td>
                  <Td>
                    <Badge tone={statusTone(d.connectionState)}>{d.connectionState}</Badge>
                  </Td>
                  <Td className="text-muted">{d.gatewayAssigned ? 'Assigned' : 'None'}</Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        </TableShell>
      ) : null}

      <section>
        <SectionTitle title="Gateways" description="Windows agents that bridge tablets to this backend" />
        {gateways.error ? <QueryError error={gateways.error} onRetry={() => void gateways.refetch()} /> : null}
        <div className="grid gap-3 sm:grid-cols-2">
          {gateways.data?.content.map((g) => (
            <Card key={g.id} className="p-4">
              <div className="flex items-start justify-between gap-2">
                <div className="font-semibold tracking-tight">{g.name}</div>
                <Badge tone={statusTone(g.status)}>{g.status}</Badge>
              </div>
              <p className="mt-2 text-sm text-muted">Last heartbeat {formatDateTime(g.lastHeartbeatAt)}</p>
              <p className="mt-2 font-mono text-[11px] text-muted">id {g.id}</p>
              {has('DEVICE_MANAGE') ? (
                <Button
                  variant="outline"
                  size="sm"
                  className="mt-3"
                  disabled={reissueEnrollment.isPending}
                  onClick={() => reissueEnrollment.mutate(g.id)}
                >
                  Reissue enrollment
                </Button>
              ) : null}
            </Card>
          ))}
        </div>
        {has('DEVICE_MANAGE') ? (
          <Card className="mt-4 max-w-lg space-y-3">
            <h3 className="text-sm font-semibold tracking-tight">Create gateway</h3>
            <p className="text-sm leading-relaxed text-muted">
              Issues a one-time enrollment token for the Windows Gateway Configurator. It is shown
              once, expires soon, and is not the long-lived credential the service stores.
            </p>
            <div>
              <Label htmlFor="gw-name">Name</Label>
              <Input id="gw-name" value={gwName} onChange={(e) => setGwName(e.target.value)} placeholder="Front desk PC" />
            </div>
            {createGateway.error ? <QueryError error={createGateway.error} /> : null}
            <Button disabled={!gwName.trim() || createGateway.isPending} onClick={() => createGateway.mutate()}>
              Issue enrollment token
            </Button>
          </Card>
        ) : null}
      </section>

      <Dialog
        open={Boolean(issued)}
        onClose={() => setIssued(null)}
        title="Enrollment token (shown once)"
        description="Enter the gateway id and this token in the Windows Gateway Configurator. Closing means you cannot retrieve the token from the app."
        className="max-w-lg"
      >
        {issued ? (
          <div className="space-y-4">
            <p className="break-all rounded-lg bg-raised p-3 font-mono text-xs leading-relaxed">{issued.token}</p>
            <p className="font-mono text-xs text-muted">gateway id {issued.id}</p>
            {issued.enrollmentExpiresAt ? (
              <p className="text-sm text-muted">Expires {formatDateTime(issued.enrollmentExpiresAt)}</p>
            ) : null}
            <div className="flex flex-wrap gap-2">
              <Button type="button" onClick={() => void navigator.clipboard.writeText(issued.token)}>
                Copy token
              </Button>
              <Button variant="outline" onClick={() => setIssued(null)}>
                I have saved it
              </Button>
            </div>
          </div>
        ) : null}
      </Dialog>
    </div>
  )
}
