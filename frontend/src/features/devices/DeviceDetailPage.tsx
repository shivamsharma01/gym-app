import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { NavLink, useNavigate, useParams } from 'react-router'
import { Badge, Button, Card, Input, Label, PageHeader, Select, Skeleton, Table, TableShell, Textarea, THead, Th, Td, Tr } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { DEVICE_MODELS } from '@/lib/catalog'
import { cn, formatDateTime } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Device, DeviceHealth, Gateway, PageResponse, SecurityEvent, SyncCommand } from '@/lib/types'

export function DeviceDetailPage() {
  const { id, section } = useParams()
  const tab = section ?? 'overview'
  const { has } = useAuth()
  const device = useQuery({ queryKey: ['device', id], queryFn: () => api<Device>(`/api/v1/devices/${id}`) })

  if (device.isLoading) return <Skeleton className="h-40" />
  if (device.error || !device.data) return <QueryError error={device.error ?? new Error('Not found')} />
  const d = device.data

  return (
    <div className="space-y-6">
      <PageHeader title={d.name} description={`${d.role} · ${d.host ?? 'no host'} · ${d.model ?? 'unknown model'}`} />
      <div className="flex flex-wrap gap-2">
        <Badge tone={statusTone(d.connectionState)}>{d.connectionState}</Badge>
        <Badge tone={d.gatewayAssigned ? 'ok' : 'warn'}>{d.gatewayAssigned ? 'Gateway assigned' : 'No gateway'}</Badge>
      </div>
      <nav className="flex flex-wrap gap-2 border-b border-line pb-3 text-sm">
        {[
          ['overview', 'Overview'],
          ['events', 'Events'],
          ['sync', 'Sync'],
          ['settings', 'Settings'],
        ].map(([key, label]) => (
          <NavLink
            key={key}
            to={key === 'overview' ? `/app/devices/${d.id}` : `/app/devices/${d.id}/${key}`}
            end={key === 'overview'}
            className={({ isActive }) =>
              cn(
                'rounded-lg px-3 py-1.5 transition',
                isActive ? 'bg-raised font-semibold text-ink shadow-[inset_0_-2px_0_0_var(--color-accent)]' : 'text-muted hover:text-ink',
              )
            }
          >
            {label}
          </NavLink>
        ))}
      </nav>
      {tab === 'overview' ? <Overview device={d} /> : null}
      {tab === 'events' ? <Events /> : null}
      {tab === 'sync' ? <Sync deviceId={d.id} /> : null}
      {tab === 'settings' ? <Settings device={d} /> : null}
      {has('DEVICE_REMOTE_CONTROL') && (tab === 'overview' || tab === 'settings') ? <DoorControl deviceId={d.id} /> : null}
    </div>
  )
}

function Overview({ device }: { device: Device }) {
  const { has } = useAuth()
  const health = useQuery({
    queryKey: ['device-health', device.id],
    queryFn: () => api<DeviceHealth>(`/api/v1/devices/${device.id}/health`),
  })
  const reconcile = useMutation({
    mutationFn: () => api<SyncCommand>(`/api/v1/devices/${device.id}/reconcile`, { method: 'POST' }),
  })

  return (
    <div className="space-y-4">
      {health.isLoading ? <Skeleton className="h-24" /> : null}
      {health.error ? <QueryError error={health.error} /> : null}
      {health.data ? (
        <div className="grid gap-3 sm:grid-cols-2">
          <Card>
            <div className="text-xs uppercase text-muted">Device</div>
            <div className="mt-1 font-semibold">{health.data.deviceConnectionState}</div>
            <p className="mt-2 text-sm text-muted">Last seen {formatDateTime(health.data.lastSeenAt)}</p>
          </Card>
          <Card>
            <div className="text-xs uppercase text-muted">Gateway</div>
            <div className="mt-1 font-semibold">{health.data.gatewayStatus ?? '—'}</div>
            <p className="mt-2 text-sm text-muted">
              Session {health.data.gatewaySessionOnline ? 'online' : 'offline'} · pending {health.data.pendingCommandCount}
            </p>
          </Card>
          <Card>
            <div className="text-xs uppercase text-muted">Last successful sync</div>
            <div className="mt-1 font-semibold">{formatDateTime(health.data.lastSuccessfulSyncAt)}</div>
          </Card>
          <Card>
            <div className="text-xs uppercase text-muted">Attendance cursor</div>
            <div className="mt-1 font-semibold">{health.data.attendanceLastRecNo ?? '—'}</div>
            <p className="mt-2 text-sm text-muted">{formatDateTime(health.data.attendanceLastEventAt)}</p>
          </Card>
        </div>
      ) : null}
      {has('DEVICE_SYNC') ? (
        <Button disabled={reconcile.isPending} onClick={() => reconcile.mutate()}>
          Request attendance reconcile
        </Button>
      ) : null}
      {reconcile.isSuccess ? (
        <p className="text-sm text-ok">Queued {reconcile.data.type} · {reconcile.data.state}</p>
      ) : null}
      {reconcile.error ? <QueryError error={reconcile.error} /> : null}
      <p className="text-sm text-muted">
        Health is not a single online/offline lamp. Device, gateway session, last sync, and pending outbox are separate
        facts.
      </p>
    </div>
  )
}

function Events() {
  const { has } = useAuth()
  const events = useQuery({
    queryKey: ['security-events'],
    queryFn: () => api<PageResponse<SecurityEvent>>('/api/v1/security-events?size=30'),
    enabled: has('SECURITY_ALERT_VIEW'),
  })
  if (!has('SECURITY_ALERT_VIEW')) {
    return <p className="text-sm text-muted">You do not have permission to view security events.</p>
  }
  if (events.isLoading) return <Skeleton className="h-32" />
  if (events.error) return <QueryError error={events.error} />
  if (!events.data?.content.length) return <p className="text-sm text-muted">No security events yet.</p>
  return (
    <ul className="divide-y divide-line rounded-xl border border-line">
      {events.data.content.map((e) => (
        <li key={e.id} className="px-4 py-3 text-sm">
          <div className="flex justify-between gap-2">
            <span className="font-semibold">{e.type}</span>
            <span className="text-muted">{formatDateTime(e.occurredAt)}</span>
          </div>
          <p className="mt-1 text-muted">{e.details ?? '—'}</p>
        </li>
      ))}
    </ul>
  )
}

function Sync({ deviceId }: { deviceId: string }) {
  const { has } = useAuth()
  const qc = useQueryClient()
  const commands = useQuery({
    queryKey: ['sync-commands', deviceId],
    queryFn: () => api<PageResponse<SyncCommand>>(`/api/v1/sync-commands?deviceId=${deviceId}&size=50`),
  })
  const retry = useMutation({
    mutationFn: (id: string) => api<SyncCommand>(`/api/v1/sync-commands/${id}/retry`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sync-commands', deviceId] }),
  })
  const cancel = useMutation({
    mutationFn: (id: string) => api<SyncCommand>(`/api/v1/sync-commands/${id}/cancel`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sync-commands', deviceId] }),
  })
  if (commands.isLoading) return <Skeleton className="h-32" />
  if (commands.error) return <QueryError error={commands.error} />
  if (!commands.data?.content.length) return <p className="text-sm text-muted">No sync commands for this device.</p>
  return (
    <TableShell>
      <Table className="min-w-[720px]">
        <THead>
          <tr>
            <Th>Type</Th>
            <Th>State</Th>
            <Th>Attempts</Th>
            <Th>Last error</Th>
            <Th />
          </tr>
        </THead>
        <tbody>
          {commands.data.content.map((c) => (
            <Tr key={c.id}>
              <Td className="font-medium">{c.type}</Td>
              <Td>
                <Badge tone={statusTone(c.state)}>{c.state}</Badge>
              </Td>
              <Td className="text-muted">
                {c.attemptCount}/{c.maxAttempts}
              </Td>
              <Td className="max-w-xs truncate text-xs text-muted">{c.lastError ?? '—'}</Td>
              <Td>
                {has('DEVICE_SYNC') ? (
                  <div className="flex gap-2">
                    <Button variant="outline" size="sm" onClick={() => retry.mutate(c.id)}>
                      Retry
                    </Button>
                    <Button variant="ghost" size="sm" onClick={() => cancel.mutate(c.id)}>
                      Cancel
                    </Button>
                  </div>
                ) : null}
              </Td>
            </Tr>
          ))}
        </tbody>
      </Table>
    </TableShell>
  )
}

function Settings({ device }: { device: Device }) {
  const { has } = useAuth()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const gateways = useQuery({
    queryKey: ['gateways'],
    queryFn: () => api<PageResponse<Gateway>>('/api/v1/gateways?size=50'),
  })
  const [name, setName] = useState(device.name)
  const [role, setRole] = useState(device.role)
  const [host, setHost] = useState(device.host ?? '')
  const [port, setPort] = useState(device.port ? String(device.port) : '')
  const [model, setModel] = useState(device.model ?? '')
  const [serialNumber, setSerialNumber] = useState(device.serialNumber ?? '')
  const [gatewayId, setGatewayId] = useState<string | undefined>(undefined)
  const onlyGateway =
    device.gatewayAssigned && gateways.data?.content.length === 1 ? gateways.data.content[0].id : ''
  const effectiveGatewayId = gatewayId !== undefined ? gatewayId : onlyGateway
  const save = useMutation({
    mutationFn: () =>
      api<Device>(`/api/v1/devices/${device.id}`, {
        method: 'PUT',
        body: JSON.stringify({
          name,
          role,
          host: host || null,
          port: port ? Number(port) : null,
          model: model || null,
          serialNumber: serialNumber || null,
          gatewayId: effectiveGatewayId || null,
        }),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['device', device.id] })
      void qc.invalidateQueries({ queryKey: ['devices'] })
      navigate(`/app/devices/${device.id}`)
    },
  })
  if (!has('DEVICE_MANAGE')) {
    return <p className="text-sm text-muted">Read-only. You need DEVICE_MANAGE to change settings.</p>
  }
  return (
    <form
      className="max-w-xl space-y-3"
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate()
      }}
    >
      <div>
        <Label>Name</Label>
        <Input value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div>
        <Label>Role</Label>
        <Select value={role} onChange={(e) => setRole(e.target.value)}>
          <option value="ENTRANCE">Entrance</option>
          <option value="EXIT">Exit</option>
          <option value="UNSPECIFIED">Unspecified</option>
        </Select>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label>Host</Label>
          <Input value={host} onChange={(e) => setHost(e.target.value)} />
        </div>
        <div>
          <Label>Port</Label>
          <Input type="number" value={port} onChange={(e) => setPort(e.target.value)} />
        </div>
      </div>
      <div>
        <Label>Model</Label>
        <Select value={model} onChange={(e) => setModel(e.target.value)}>
          {DEVICE_MODELS.map((m) => (
            <option key={m} value={m}>
              {m}
            </option>
          ))}
          {model && !(DEVICE_MODELS as readonly string[]).includes(model) ? (
            <option value={model}>{model}</option>
          ) : null}
          <option value="">Unknown / other</option>
        </Select>
      </div>
      <div>
        <Label>Serial</Label>
        <Input value={serialNumber} onChange={(e) => setSerialNumber(e.target.value)} />
      </div>
      <div>
        <Label>Assign gateway</Label>
        <Select value={effectiveGatewayId} onChange={(e) => setGatewayId(e.target.value)}>
          <option value="">Unassign gateway</option>
          {gateways.data?.content.map((g) => (
            <option key={g.id} value={g.id}>
              {g.name}
            </option>
          ))}
        </Select>
      </div>
      {save.error ? <QueryError error={save.error} /> : null}
      <Button type="submit" disabled={save.isPending}>
        Save
      </Button>
    </form>
  )
}

function DoorControl({ deviceId }: { deviceId: string }) {
  const [action, setAction] = useState('OPEN')
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const door = useMutation({
    mutationFn: () =>
      api<SyncCommand>(`/api/v1/devices/${deviceId}/door`, {
        method: 'POST',
        body: JSON.stringify({ action, confirmed: true, reason }),
      }),
  })
  return (
    <Card className="max-w-lg space-y-3">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-muted">Remote door</h2>
      <p className="text-sm text-muted">
        High-risk physical operation. Requires confirmation, a reason, and DEVICE_REMOTE_CONTROL. The command is queued;
        this screen does not claim the lock moved.
      </p>
      <Select value={action} onChange={(e) => setAction(e.target.value)}>
        <option value="OPEN">Open</option>
        <option value="CLOSE">Close</option>
      </Select>
      <Textarea rows={2} placeholder="Reason" value={reason} onChange={(e) => setReason(e.target.value)} />
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
        I confirm this remote door command
      </label>
      <Button
        variant="danger"
        disabled={!confirmed || !reason.trim() || door.isPending}
        onClick={() => door.mutate()}
      >
        Queue {action.toLowerCase()}
      </Button>
      {door.isSuccess ? (
        <p className="text-sm text-ok">
          Queued {door.data.type} · {door.data.state}. Wait for the gateway result; do not assume the door moved.
        </p>
      ) : null}
      {door.error ? <QueryError error={door.error} /> : null}
    </Card>
  )
}
