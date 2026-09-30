import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link, NavLink, Navigate, useNavigate, useParams } from 'react-router'
import { Badge, Button, Card, Input, Label, PageHeader, Select, Skeleton, Table, TableShell, Textarea, THead, Th, Td, Tr } from '@/components/ui'
import { PageNav } from '@/components/Pager'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { DEVICE_MODELS } from '@/lib/catalog'
import { cn, formatDateTime } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Device, DeviceHealth, Gateway, ImportUsersResult, PageResponse, ReconciliationConflict, SecurityEvent, SyncCommand } from '@/lib/types'

export function DeviceDetailPage() {
  const { id, section } = useParams()
  const tab = section ?? 'overview'
  const { has } = useAuth()
  const device = useQuery({ queryKey: ['device', id], queryFn: () => api<Device>(`/api/v1/devices/${id}`) })

  if (device.isLoading) return <Skeleton className="h-40" />
  if (device.error || !device.data) return <QueryError error={device.error ?? new Error('Not found')} />
  const d = device.data
  const showEvents = has('SECURITY_ALERT_VIEW')
  const showSettings = has('DEVICE_MANAGE')
  if ((tab === 'events' && !showEvents) || (tab === 'settings' && !showSettings)) {
    return <Navigate to={`/app/devices/${d.id}`} replace />
  }

  return (
    <div className="space-y-6">
      <Link
        to="/app/devices"
        className="inline-flex items-center gap-1.5 text-sm text-muted transition-colors hover:text-ink"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to devices
      </Link>
      <PageHeader
        title={d.name}
        description={`${d.role} · ${d.host ?? 'no host'} · ${d.model ?? 'unknown model'}`}
        actions={
          has('DEVICE_MANAGE') ? (
            <Link to="/app/devices/new">
              <Button variant="outline">Register another device</Button>
            </Link>
          ) : null
        }
      />
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={statusTone(d.connectionState)}>{d.connectionState}</Badge>
        <Badge tone={d.gatewayAssigned ? 'ok' : 'warn'}>{d.gatewayAssigned ? 'Gateway assigned' : 'No gateway'}</Badge>
      </div>
      <p className="flex flex-wrap items-center gap-2 font-mono text-xs text-muted">
        <span>Device id</span>
        <code className="rounded bg-raised px-1.5 py-0.5 text-ink">{d.id}</code>
        <Button
          type="button"
          size="sm"
          variant="ghost"
          onClick={() => void navigator.clipboard.writeText(d.id)}
        >
          Copy
        </Button>
      </p>
      <nav className="flex flex-wrap gap-2 border-b border-line pb-3 text-sm">
        {[
          ['overview', 'Overview'],
          ...(showEvents ? [['events', 'Events']] : []),
          ['sync', 'Sync'],
          ...(showSettings ? [['settings', 'Settings']] : []),
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
  const navigate = useNavigate()
  const qc = useQueryClient()
  const health = useQuery({
    queryKey: ['device-health', device.id],
    queryFn: () => api<DeviceHealth>(`/api/v1/devices/${device.id}/health`),
  })
  const [conflictPage, setConflictPage] = useState(0)
  const conflicts = useQuery({
    queryKey: ['device-conflicts', device.id, conflictPage],
    queryFn: () =>
      api<PageResponse<ReconciliationConflict>>(`/api/v1/devices/${device.id}/conflicts?page=${conflictPage}&size=20`),
    placeholderData: keepPreviousData,
  })
  const reconcile = useMutation({
    mutationFn: () => api<SyncCommand>(`/api/v1/devices/${device.id}/reconcile`, { method: 'POST' }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['device-health', device.id] })
      void qc.invalidateQueries({ queryKey: ['sync-commands', device.id] })
    },
  })
  const syncNow = useMutation({
    mutationFn: () => api<SyncCommand>(`/api/v1/devices/${device.id}/sync-now`, { method: 'POST' }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['device-health', device.id] })
      void qc.invalidateQueries({ queryKey: ['sync-commands', device.id] })
    },
  })
  const importUsers = useMutation({
    mutationFn: () => api<ImportUsersResult>(`/api/v1/devices/${device.id}/import-users`, { method: 'POST' }),
    onSuccess: () => {
      void health.refetch()
      void conflicts.refetch()
    },
  })
  const resolveConflict = useMutation({
    mutationFn: ({ id, action }: { id: string; action: string }) =>
      api<ReconciliationConflict>(`/api/v1/devices/${device.id}/conflicts/${id}/resolve?action=${action}`, {
        method: 'POST',
      }),
    onSuccess: () => {
      void health.refetch()
      void conflicts.refetch()
    },
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
              Session {health.data.gatewaySessionOnline ? 'online' : 'offline'} ·{' '}
              <button
                type="button"
                className="font-medium text-accent underline-offset-2 hover:underline"
                onClick={() => navigate(`/app/devices/${device.id}/sync`)}
              >
                pending {health.data.pendingCommandCount}
                {health.data.pendingCommandCount > 0 ? (
                  <span className="ml-1.5 inline-flex align-middle">
                    <Badge tone="warn">unsynced</Badge>
                  </span>
                ) : null}
              </button>
              {' · '}failed {health.data.failedCommandCount}
            </p>
          </Card>
          <Card>
            <div className="text-xs uppercase text-muted">Last successful auth sync</div>
            <div className="mt-1 font-semibold">{formatDateTime(health.data.lastSuccessfulSyncAt)}</div>
          </Card>
          <Card>
            <div className="text-xs uppercase text-muted">Attendance</div>
            <div className="mt-1 font-semibold">
              cursor {health.data.attendanceLastRecNo ?? '—'}
              {health.data.reconciliationRequired ? (
                <span className="ml-2">
                  <Badge tone="warn">Reconcile required</Badge>
                </span>
              ) : null}
            </div>
            <p className="mt-2 text-sm text-muted">
              Last sync {formatDateTime(health.data.lastAttendanceSyncAt ?? health.data.attendanceLastEventAt)} ·
              conflicts {health.data.openConflictCount}
            </p>
          </Card>
        </div>
      ) : null}
      {has('DEVICE_SYNC') ? (
        <div className="flex flex-wrap gap-2">
          <Button disabled={syncNow.isPending} onClick={() => syncNow.mutate()}>
            Sync Now
          </Button>
          <Button variant="outline" disabled={reconcile.isPending} onClick={() => reconcile.mutate()}>
            Request attendance reconcile
          </Button>
        </div>
      ) : null}
      {has('DEVICE_MANAGE') ? (
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            disabled={importUsers.isPending}
            onClick={() => importUsers.mutate()}
          >
            Import device users
          </Button>
          <span className="text-xs text-muted">
            Creates Members from the last synced device roster (idempotent). Run Sync Now first if empty.
          </span>
        </div>
      ) : null}
      {syncNow.isSuccess ? (
        <p className="text-sm text-ok">Queued {syncNow.data.type} · {syncNow.data.state}</p>
      ) : null}
      {reconcile.isSuccess ? (
        <p className="text-sm text-ok">Queued {reconcile.data.type} · {reconcile.data.state}</p>
      ) : null}
      {importUsers.isSuccess ? (
        <p className="text-sm text-ok">
          Import: created {importUsers.data.created}, mapped {importUsers.data.mapped}, skipped{' '}
          {importUsers.data.skipped}, frozen→inactive {importUsers.data.inactiveFrozen}, inferred end dates{' '}
          {importUsers.data.inferredEndDates} (saw {importUsers.data.deviceUsersSeen} device users)
        </p>
      ) : null}
      {syncNow.error ? <QueryError error={syncNow.error} /> : null}
      {reconcile.error ? <QueryError error={reconcile.error} /> : null}
      {importUsers.error ? <QueryError error={importUsers.error} /> : null}
      {conflicts.data && conflicts.data.content.length > 0 ? (
        <Card className="space-y-3">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-muted">
            Reconciliation conflicts ({conflicts.data.totalElements})
          </h2>
          <ul className="divide-y divide-line">
            {conflicts.data.content.map((c) => (
              <li key={c.id} className="flex flex-wrap items-start justify-between gap-2 py-2 text-sm">
                <div>
                  <div className="font-semibold">
                    {c.conflictType} · {c.deviceUserId}
                  </div>
                  <p className="text-muted">{c.details ?? '—'}</p>
                </div>
                {has('DEVICE_SYNC') ? (
                  <div className="flex gap-2">
                    {c.conflictType === 'EXTRA_DEVICE_USER' || c.conflictType === 'AUTH_MISMATCH' ? (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => resolveConflict.mutate({ id: c.id, action: 'REMOVE' })}
                      >
                        Remove from device
                      </Button>
                    ) : null}
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => resolveConflict.mutate({ id: c.id, action: 'DISMISS' })}
                    >
                      Dismiss
                    </Button>
                  </div>
                ) : null}
              </li>
            ))}
          </ul>
          <PageNav data={conflicts.data} onPageChange={setConflictPage} />
        </Card>
      ) : null}
      <p className="text-sm text-muted">
        Health is not a single online/offline lamp. Device, gateway session, last sync, pending/failed outbox, and
        reconciliation conflicts are separate facts.
      </p>
    </div>
  )
}

function Events() {
  const { has } = useAuth()
  const [page, setPage] = useState(0)
  const events = useQuery({
    queryKey: ['security-events', page],
    queryFn: () => api<PageResponse<SecurityEvent>>(`/api/v1/security-events?page=${page}&size=30`),
    enabled: has('SECURITY_ALERT_VIEW'),
    placeholderData: keepPreviousData,
  })
  if (events.isLoading) return <Skeleton className="h-32" />
  if (events.error) return <QueryError error={events.error} />
  if (!events.data?.content.length) return <p className="text-sm text-muted">No security events yet.</p>
  return (
    <div>
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
      <PageNav data={events.data} onPageChange={setPage} />
    </div>
  )
}

function Sync({ deviceId }: { deviceId: string }) {
  const { has } = useAuth()
  const qc = useQueryClient()
  const [openOnly, setOpenOnly] = useState(true)
  const [page, setPage] = useState(0)
  const commands = useQuery({
    queryKey: ['sync-commands', deviceId, openOnly, page],
    queryFn: () =>
      api<PageResponse<SyncCommand>>(
        `/api/v1/sync-commands?deviceId=${deviceId}&page=${page}&size=25&openOnly=${openOnly}`,
      ),
    refetchInterval: 15_000,
    placeholderData: keepPreviousData,
  })
  const retry = useMutation({
    mutationFn: (id: string) => api<SyncCommand>(`/api/v1/sync-commands/${id}/retry`, { method: 'POST' }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['sync-commands', deviceId] })
      void qc.invalidateQueries({ queryKey: ['device-health', deviceId] })
    },
  })
  const cancel = useMutation({
    mutationFn: (id: string) => api<SyncCommand>(`/api/v1/sync-commands/${id}/cancel`, { method: 'POST' }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['sync-commands', deviceId] })
      void qc.invalidateQueries({ queryKey: ['device-health', deviceId] })
    },
  })
  const openStates = new Set(['PENDING', 'DISPATCHED', 'ACKNOWLEDGED', 'RETRYING'])

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted">
          Pending means saved on the server, not yet confirmed on this device. If the gateway is offline, commands stay
          here until they can be delivered.
        </p>
        <div className="flex gap-2">
          <Button size="sm" variant={openOnly ? 'primary' : 'outline'} onClick={() => { setOpenOnly(true); setPage(0) }}>
            Open queue
          </Button>
          <Button size="sm" variant={!openOnly ? 'primary' : 'outline'} onClick={() => { setOpenOnly(false); setPage(0) }}>
            All history
          </Button>
        </div>
      </div>
      {commands.isLoading ? <Skeleton className="h-32" /> : null}
      {commands.error ? <QueryError error={commands.error} /> : null}
      {commands.data && commands.data.content.length === 0 ? (
        <p className="text-sm text-muted">
          {openOnly ? 'No open sync commands for this device.' : 'No sync commands for this device.'}
        </p>
      ) : null}
      {commands.data && commands.data.content.length > 0 ? (
        <TableShell>
          <Table className="min-w-[820px]">
            <THead>
              <tr>
                <Th>Type</Th>
                <Th>Member</Th>
                <Th>State</Th>
                <Th>Attempts</Th>
                <Th>When</Th>
                <Th>Last error</Th>
                <Th />
              </tr>
            </THead>
            <tbody>
              {commands.data.content.map((c) => {
                const canAct = openStates.has(c.state) || c.state === 'DEAD_LETTER' || c.state === 'FAILED'
                return (
                  <Tr key={c.id}>
                    <Td className="font-medium">{c.type}</Td>
                    <Td>
                      <div className="font-medium">{c.memberName ?? '—'}</div>
                      <div className="font-mono text-[11px] text-muted">{c.deviceUserId ?? ''}</div>
                    </Td>
                    <Td>
                      <Badge tone={statusTone(c.state)}>{c.state}</Badge>
                    </Td>
                    <Td className="text-muted">
                      {c.attemptCount}/{c.maxAttempts}
                    </Td>
                    <Td className="whitespace-nowrap text-xs text-muted">{formatDateTime(c.createdAt)}</Td>
                    <Td className="max-w-xs truncate text-xs text-muted">{c.lastError ?? '—'}</Td>
                    <Td>
                      {has('DEVICE_SYNC') && canAct ? (
                        <div className="flex gap-2">
                          <Button variant="outline" size="sm" onClick={() => retry.mutate(c.id)}>
                            Retry
                          </Button>
                          {openStates.has(c.state) ? (
                            <Button variant="ghost" size="sm" onClick={() => cancel.mutate(c.id)}>
                              Cancel
                            </Button>
                          ) : null}
                        </div>
                      ) : null}
                    </Td>
                  </Tr>
                )
              })}
            </tbody>
          </Table>
        </TableShell>
      ) : null}
      {commands.data && commands.data.content.length > 0 ? (
        <PageNav data={commands.data} onPageChange={setPage} />
      ) : null}
    </div>
  )
}

function Settings({ device }: { device: Device }) {
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
        High-risk physical operation. Requires confirmation and a reason. The command is queued;
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
