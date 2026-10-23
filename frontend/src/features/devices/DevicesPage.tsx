import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { MonitorSmartphone } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { Dialog } from '@/components/Dialog'
import { PageNav } from '@/components/Pager'
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
import { toastOk } from '@/lib/toast'
import { useAuth } from '@/lib/useAuth'
import { formatDateTime } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Device, Gateway, GatewayCreated, PageResponse } from '@/lib/types'

export function DevicesPage() {
  const { has } = useAuth()
  const qc = useQueryClient()
  const [devicePage, setDevicePage] = useState(0)
  const devices = useQuery({
    queryKey: ['devices', 'page', devicePage],
    queryFn: () => api<PageResponse<Device>>(`/api/v1/devices?page=${devicePage}&size=20`),
    placeholderData: keepPreviousData,
  })
  const gateways = useQuery({
    queryKey: ['gateways'],
    queryFn: () => api<PageResponse<Gateway>>('/api/v1/gateways?size=1'),
    placeholderData: keepPreviousData,
  })
  const [gwName, setGwName] = useState('')
  const [issued, setIssued] = useState<GatewayCreated | null>(null)
  const createGateway = useMutation({
    mutationFn: () => api<GatewayCreated>('/api/v1/gateways', { method: 'POST', body: JSON.stringify({ name: gwName }) }),
    onSuccess: (created) => {
      setIssued(created)
      setGwName('')
      void qc.invalidateQueries({ queryKey: ['gateways'] })
      toastOk('Gym PC added. Copy the setup code.')
    },
  })
  const reissueEnrollment = useMutation({
    mutationFn: (id: string) =>
      api<GatewayCreated>(`/api/v1/gateways/${id}/enrollment`, { method: 'POST' }),
    onSuccess: (created) => {
      setIssued(created)
      toastOk('New setup code ready.')
    },
  })
  const gateway = gateways.data?.content[0]

  return (
    <div className="space-y-10">
      <PageHeader
        title="Devices"
        description="Readers on the gym PC. Add a reader, then connect it."
        actions={
          has('DEVICE_MANAGE') ? (
            <Link to="/app/devices/new">
              <Button>Add a reader</Button>
            </Link>
          ) : null
        }
      />

      {devices.isLoading ? <Skeleton className="h-32" /> : null}
      {devices.error ? <QueryError error={devices.error} onRetry={() => void devices.refetch()} /> : null}
      {devices.data && devices.data.content.length === 0 ? (
        <EmptyState
          title="No readers"
          body="Add the gym PC first, then add a reader."
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
                    <div className="mt-0.5 font-mono text-[11px] text-muted">{d.id}</div>
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
      {devices.data && devices.data.content.length > 0 ? (
        <PageNav data={devices.data} onPageChange={setDevicePage} />
      ) : null}

      <section>
        <SectionTitle title="Gym PC" description="The computer that talks to the readers" />
        {gateways.error ? <QueryError error={gateways.error} onRetry={() => void gateways.refetch()} /> : null}
        {gateway ? (
          <Card className="max-w-lg p-4">
            <div className="flex items-start justify-between gap-2">
              <div className="font-semibold tracking-tight">{gateway.name}</div>
              <Badge tone={statusTone(gateway.status)}>{gateway.status}</Badge>
            </div>
            <p className="mt-2 text-sm text-muted">Last heartbeat {formatDateTime(gateway.lastHeartbeatAt)}</p>
            <p className="mt-2 font-mono text-[11px] text-muted">id {gateway.id}</p>
            {has('DEVICE_MANAGE') ? (
              <Button
                variant="outline"
                size="sm"
                className="mt-3"
                disabled={reissueEnrollment.isPending}
                onClick={() => reissueEnrollment.mutate(gateway.id)}
              >
                {reissueEnrollment.isPending ? 'Creating code…' : 'New setup code'}
              </Button>
            ) : null}
          </Card>
        ) : null}
        {has('DEVICE_MANAGE') && gateways.data?.content.length === 0 ? (
          <Card className="mt-4 max-w-lg space-y-3">
            <h3 className="text-sm font-semibold tracking-tight">Add the gym PC</h3>
            <p className="text-sm leading-relaxed text-muted">
              Creates a one-time code for the gym PC. It is shown once.
            </p>
            <div>
              <Label htmlFor="gw-name">Name</Label>
              <Input id="gw-name" value={gwName} onChange={(e) => setGwName(e.target.value)} placeholder="Front desk PC" />
            </div>
            {createGateway.error ? <QueryError error={createGateway.error} /> : null}
            <Button disabled={!gwName.trim() || createGateway.isPending} onClick={() => createGateway.mutate()}>
              {createGateway.isPending ? 'Creating…' : 'Create setup code'}
            </Button>
          </Card>
        ) : null}
      </section>

      <Dialog
        open={Boolean(issued)}
        onClose={() => setIssued(null)}
        title="Setup code (shown once)"
        description="Enter this id and code on the gym PC. You cannot see the code again after you close this."
        className="max-w-lg"
      >
        {issued ? (
          <div className="space-y-4">
            <p className="break-all rounded-lg bg-raised p-3 font-mono text-xs leading-relaxed">{issued.token}</p>
            <p className="font-mono text-xs text-muted">gym PC id {issued.id}</p>
            {issued.enrollmentExpiresAt ? (
              <p className="text-sm text-muted">Expires {formatDateTime(issued.enrollmentExpiresAt)}</p>
            ) : null}
            <div className="flex flex-wrap gap-2">
              <Button type="button" onClick={() => void navigator.clipboard.writeText(issued.token)}>
                Copy code
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
