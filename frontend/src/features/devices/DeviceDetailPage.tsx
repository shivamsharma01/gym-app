import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, NavLink, Navigate, useNavigate, useParams } from 'react-router'
import { BackLink } from '@/components/BackLink'
import { Badge, Button, Card, Input, Label, PageHeader, Select, Skeleton, Table, TableShell, Textarea, THead, Th, Td, Tr } from '@/components/ui'
import { PageNav } from '@/components/Pager'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { toastOk } from '@/lib/toast'
import { useAuth } from '@/lib/useAuth'
import { DEVICE_MODELS } from '@/lib/catalog'
import { cn, formatDateTime } from '@/lib/cn'
import { readerConnection, statusTone } from '@/lib/status'
import type { Device, DeviceHealth, Gateway, PageResponse, SecurityEvent, SyncCommand } from '@/lib/types'

export function DeviceDetailPage() {
  const { id, section } = useParams()
  const tab = section ?? 'overview'
  const { has } = useAuth()
  const device = useQuery({ queryKey: ['device', id], queryFn: () => api<Device>(`/api/v1/devices/${id}`) })
  const health = useQuery({
    queryKey: ['device-health', id],
    queryFn: () => api<DeviceHealth>(`/api/v1/devices/${id}/health`),
    enabled: Boolean(id),
  })

  if (device.isLoading) return <Skeleton className="h-40" />
  if (device.error || !device.data) return <QueryError error={device.error ?? new Error('Not found')} />
  const d = device.data
  const connection = readerConnection(d.connectionState, health.data?.gatewayStatus, d.gatewayAssigned)
  const showEvents = has('SECURITY_ALERT_VIEW')
  const showSettings = has('DEVICE_MANAGE')
  if ((tab === 'events' && !showEvents) || (tab === 'settings' && !showSettings)) {
    return <Navigate to={`/app/devices/${d.id}`} replace />
  }

  return (
    <div className="space-y-6">
      <BackLink to="/app/devices" label="Back to devices" />
      <PageHeader
        title={d.name}
        description={`${d.role} · ${d.host ?? 'no host'} · ${d.model ?? 'unknown model'}`}
        actions={
          has('DEVICE_MANAGE') ? (
            <Link to="/app/devices/new">
              <Button variant="outline">Add a reader</Button>
            </Link>
          ) : null
        }
      />
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={statusTone(connection)}>{connection}</Badge>
        <Badge tone={d.gatewayAssigned ? 'ok' : 'warn'}>{d.gatewayAssigned ? 'Connected to the gym PC' : 'Not connected to the gym PC'}</Badge>
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
          ['sync', 'Updates'],
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
      {tab === 'events' ? <Events deviceId={d.id} /> : null}
      {tab === 'sync' ? <Sync deviceId={d.id} /> : null}
      {tab === 'settings' ? <Settings device={d} /> : null}
      {has('DEVICE_REMOTE_CONTROL') && (tab === 'overview' || tab === 'settings') ? <DoorControl deviceId={d.id} /> : null}
    </div>
  )
}

function Overview({ device }: { device: Device }) {
  const { has } = useAuth()
  const navigate = useNavigate()
  const health = useQuery({
    queryKey: ['device-health', device.id],
    queryFn: () => api<DeviceHealth>(`/api/v1/devices/${device.id}/health`),
  })

  return (
    <div className="space-y-4">
      {health.isLoading ? <Skeleton className="h-24" /> : null}
      {health.error ? <QueryError error={health.error} /> : null}
      {health.data ? (
        <div className="grid gap-3 sm:grid-cols-2">
          <Card>
            <div className="text-xs uppercase text-muted">Device</div>
            <div className="mt-1 font-semibold">
              {readerConnection(health.data.deviceConnectionState, health.data.gatewayStatus, device.gatewayAssigned)}
            </div>
            <p className="mt-2 text-sm text-muted">Last seen {formatDateTime(health.data.lastSeenAt)}</p>
          </Card>
          <Card>
            <div className="text-xs uppercase text-muted">Gym PC</div>
            <div className="mt-1 font-semibold">{health.data.gatewayStatus ?? '—'}</div>
            <p className="mt-2 text-sm text-muted">
              {health.data.gatewaySessionOnline ? 'Online' : 'Offline'} ·{' '}
              <button
                type="button"
                className="font-medium text-accent underline-offset-2 hover:underline"
                onClick={() => navigate(`/app/devices/${device.id}/sync`)}
              >
                {health.data.pendingCommandCount} waiting
              </button>
              {' · '}
              {health.data.failedCommandCount} failed
            </p>
          </Card>
          <Card>
            <div className="text-xs uppercase text-muted">Last update confirmed</div>
            <div className="mt-1 font-semibold">{formatDateTime(health.data.lastSuccessfulSyncAt)}</div>
          </Card>
          <Card>
            <div className="text-xs uppercase text-muted">Attendance</div>
            <div className="mt-1 font-semibold">Last punch stored {formatDateTime(health.data.lastPunchAt)}</div>
            <p className="mt-2 text-sm text-muted">Punches are read from the reader every few seconds.</p>
          </Card>
        </div>
      ) : null}
      {has('DEVICE_SYNC') && has('REVIEW_DECIDE') && device.gatewayAssigned ? (
        <>
          <ImportPending deviceId={device.id} />
          <LinkMatches deviceId={device.id} />
        </>
      ) : null}
      {has('DEVICE_SYNC') && device.gatewayAssigned ? <Reconcile deviceId={device.id} /> : null}
      <p className="text-sm text-muted">
        Health shows the reader, the gym PC, the last update, and anything still waiting or failed.
      </p>
    </div>
  )
}

type ImportSummary = {
  deviceId: string
  observed: number
  withPhoto: number
  withoutPhoto: number
  pendingUndecided: number
  possibleNameMatch: number
  possibleFaceMatch: number
  readyToCreate: number
  created: number
  skipped: number
  mode: string
}

type ImportResult = {
  deviceId: string
  created: number
  skipped: { deviceUserId: string; reason: string }[]
  summary?: ImportSummary
}

function ImportPending({ deviceId }: Readonly<{ deviceId: string }>) {
  const qc = useQueryClient()
  const run = useMutation({
    mutationFn: (mode: 'CONSERVATIVE' | 'DISTINCT_SOURCE') =>
      api<ImportResult>(`/api/v1/devices/${deviceId}/import-pending?mode=${mode}`, { method: 'POST' }),
    onSuccess: (result) => {
      void qc.invalidateQueries({ queryKey: ['reviews'] })
      const skipped = result.skipped?.length ?? 0
      if (result.created === 0) {
        toastOk(skipped > 0 ? `No new members. ${skipped} were already in the gym.` : 'No new members.')
      } else {
        toastOk(`Added ${result.created} member${result.created === 1 ? '' : 's'}.`)
      }
    },
  })
  const created = run.data?.created ?? 0
  const skipped = run.data?.skipped ?? []
  return (
    <Card className="space-y-3">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-muted">People on this reader</h2>
      <p className="text-sm text-muted">
        Adds them as members. The reader is not changed. Skip likely matches leaves anyone who might already be a
        member for you to check. Add one member per person skips only an identical photo.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant="outline" disabled={run.isPending} onClick={() => run.mutate('CONSERVATIVE')}>
          {run.isPending && run.variables === 'CONSERVATIVE' ? 'Adding…' : 'Skip likely matches'}
        </Button>
        <Button size="sm" variant="outline" disabled={run.isPending} onClick={() => run.mutate('DISTINCT_SOURCE')}>
          {run.isPending && run.variables === 'DISTINCT_SOURCE' ? 'Adding…' : 'Add one member per person'}
        </Button>
      </div>
      {run.isSuccess ? (
        <p className="text-sm text-muted">
          Added {created}
          {skipped.length > 0 ? `. Skipped ${skipped.length}, already in the gym` : ''}.
        </p>
      ) : null}
      {skipped.length > 0 ? (
        <ul className="max-h-48 space-y-1 overflow-y-auto text-sm text-muted">
          {skipped.map((item) => (
            <li key={item.deviceUserId}>
              Id {item.deviceUserId}: {item.reason}
            </li>
          ))}
        </ul>
      ) : null}
      {run.error ? <QueryError error={run.error} /> : null}
    </Card>
  )
}

type LinkMatch = {
  enrollmentId: string
  deviceUserId: string
  readerName: string
  validFrom: string | null
  validTo: string | null
  memberId: string
  memberCode: string
  memberName: string
  otherReaderName: string
}

function LinkMatches({ deviceId }: Readonly<{ deviceId: string }>) {
  const qc = useQueryClient()
  const preview = useMutation({
    mutationFn: () => api<LinkMatch[]>(`/api/v1/devices/${deviceId}/link-matches`),
  })
  const link = useMutation({
    mutationFn: (rows: LinkMatch[]) =>
      api<{ linked: number; notLinked: string[] }>(`/api/v1/devices/${deviceId}/link-matches`, {
        method: 'POST',
        body: JSON.stringify({ links: rows.map((row) => ({ enrollmentId: row.enrollmentId, memberId: row.memberId })) }),
      }),
    onSuccess: (result) => {
      preview.reset()
      void qc.invalidateQueries({ queryKey: ['reviews'] })
      toastOk(result.linked === 0 ? 'No one was linked.' : `Linked ${result.linked}.`)
    },
  })
  const rows = preview.data ?? []
  return (
    <Card className="space-y-3">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-muted">Same people as another reader</h2>
      <p className="text-sm text-muted">
        Finds people here who match one member already in the gym. Check the list, then link them.
      </p>
      <Button size="sm" variant="outline" disabled={preview.isPending || link.isPending} onClick={() => preview.mutate()}>
        {preview.isPending ? 'Looking…' : 'Find matches'}
      </Button>
      {preview.isSuccess && rows.length === 0 ? <p className="text-sm text-muted">No exact matches.</p> : null}
      {rows.length > 0 ? (
        <>
          <TableShell>
            <Table>
              <THead>
                <Tr>
                  <Th>Reader id</Th>
                  <Th>On this reader</Th>
                  <Th>Dates</Th>
                  <Th>Member</Th>
                  <Th>Already on</Th>
                </Tr>
              </THead>
              <tbody>
                {rows.map((row) => (
                  <Tr key={row.enrollmentId}>
                    <Td className="font-mono text-xs">{row.deviceUserId}</Td>
                    <Td>{row.readerName}</Td>
                    <Td className="font-mono text-xs">
                      {row.validFrom ?? '…'} – {row.validTo ?? '…'}
                    </Td>
                    <Td>
                      {row.memberName} <span className="text-xs text-muted">{row.memberCode}</span>
                    </Td>
                    <Td>{row.otherReaderName}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
          </TableShell>
          <Button size="sm" disabled={link.isPending} onClick={() => link.mutate(rows)}>
            {link.isPending ? 'Linking…' : `Link these ${rows.length}`}
          </Button>
        </>
      ) : null}
      {link.isSuccess ? (
        <p className="text-sm text-muted">
          Linked {link.data.linked}
          {link.data.notLinked.length > 0 ? `. ${link.data.notLinked.length} no longer matched` : ''}.
        </p>
      ) : null}
      {preview.error ? <QueryError error={preview.error} /> : null}
      {link.error ? <QueryError error={link.error} /> : null}
    </Card>
  )
}

function Reconcile({ deviceId }: Readonly<{ deviceId: string }>) {
  const reconcile = useMutation({
    mutationFn: () =>
      api<{ deviceId: string; written: number; held?: number }>(`/api/v1/devices/${deviceId}/reconcile`, {
        method: 'POST',
      }),
    onSuccess: (result) => {
      const written = result.written ?? 0
      const held = result.held ?? 0
      if (written === 0 && held === 0) toastOk('Everyone who should be here already is.')
      else if (held > 0) toastOk(`Sending ${written}. ${held} are waiting on Review.`)
      else toastOk(`Sending ${written} member${written === 1 ? '' : 's'} to this reader.`)
    },
  })
  const written = reconcile.data?.written ?? 0
  const held = reconcile.data?.held ?? 0
  const queuedMessage = `Sending ${written} member${written === 1 ? '' : 's'} to this reader.`
  const heldMessage = ` ${held} waiting until you finish Review.`
  return (
    <Card className="space-y-3">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-muted">Send members to this reader</h2>
      <p className="text-sm text-muted">
        Adds members who have a photo and are not on this reader yet. People already here are left as they are.
      </p>
      <Button size="sm" variant="outline" disabled={reconcile.isPending} onClick={() => reconcile.mutate()}>
        {reconcile.isPending ? 'Sending…' : 'Send missing members'}
      </Button>
      {reconcile.isSuccess ? (
        <p className="text-sm text-muted">
          {written === 0 && held === 0 ? 'Nothing was missing.' : null}
          {written > 0 ? queuedMessage : null}
          {held > 0 ? heldMessage : null}
        </p>
      ) : null}
      {reconcile.error ? <QueryError error={reconcile.error} /> : null}
      <ScanNow deviceId={deviceId} />
    </Card>
  )
}

const FINISHED_COMMAND_STATES = new Set(['SUCCEEDED', 'DEAD_LETTER', 'CANCELLED'])

function ScanNow({ deviceId }: Readonly<{ deviceId: string }>) {
  const scan = useMutation({
    mutationFn: () => api<SyncCommand>(`/api/v1/devices/${deviceId}/scan`, { method: 'POST' }),
    onSuccess: () => toastOk('Reading the reader now.'),
  })
  const commandId = scan.data?.id
  const command = useQuery({
    queryKey: ['sync-command', commandId],
    queryFn: () => api<SyncCommand>(`/api/v1/sync-commands/${commandId}`),
    enabled: !!commandId,
    refetchInterval: (query) =>
      query.state.data && FINISHED_COMMAND_STATES.has(query.state.data.state) ? false : 3000,
  })
  const current = command.data ?? scan.data
  const state = current?.state
  const running = scan.isPending || (!!state && !FINISHED_COMMAND_STATES.has(state))
  return (
    <div className="space-y-2 border-t border-line pt-3">
      <p className="text-sm text-muted">Checks the reader now instead of waiting for the next read.</p>
      <Button size="sm" variant="outline" disabled={running} onClick={() => scan.mutate()}>
        {running ? 'Reading…' : 'Read this reader'}
      </Button>
      {state === 'SUCCEEDED' ? (
        <p className="text-sm text-ok">Reader list received.</p>
      ) : null}
      {state && !FINISHED_COMMAND_STATES.has(state) ? (
        <p className="text-sm text-muted">Waiting for the reader{current?.lastError ? `: ${current.lastError}` : '.'}</p>
      ) : null}
      {state === 'DEAD_LETTER' || state === 'CANCELLED' ? (
        <p className="text-sm text-danger">
          The reader was not read{current?.lastError ? `: ${current.lastError}` : '.'}
        </p>
      ) : null}
      {scan.error ? <QueryError error={scan.error} /> : null}
    </div>
  )
}

function Events({ deviceId }: Readonly<{ deviceId: string }>) {
  const { has } = useAuth()
  const [page, setPage] = useState(0)
  const events = useQuery({
    queryKey: ['security-events', deviceId, page],
    queryFn: () =>
      api<PageResponse<SecurityEvent>>(`/api/v1/security-events?deviceId=${deviceId}&page=${page}&size=30`),
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
      toastOk('Trying again.')
      void qc.invalidateQueries({ queryKey: ['sync-commands', deviceId] })
      void qc.invalidateQueries({ queryKey: ['device-health', deviceId] })
    },
  })
  const cancel = useMutation({
    mutationFn: (id: string) => api<SyncCommand>(`/api/v1/sync-commands/${id}/cancel`, { method: 'POST' }),
    onSuccess: () => {
      toastOk('Cancelled.')
      void qc.invalidateQueries({ queryKey: ['sync-commands', deviceId] })
      void qc.invalidateQueries({ queryKey: ['device-health', deviceId] })
    },
  })
  const openStates = new Set(['PENDING', 'DISPATCHED', 'ACKNOWLEDGED', 'RETRYING'])

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted">
          Pending means saved here, not yet confirmed on the reader. If the gym PC is offline, they wait here.
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
          {openOnly ? 'Nothing waiting for this reader.' : 'No past updates for this reader.'}
        </p>
      ) : null}
      {commands.data && commands.data.content.length > 0 ? (
        <TableShell>
          <Table className="min-w-[820px]">
            <THead>
              <tr>
                <Th>Type</Th>
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
  const gateway = gateways.data?.content[0]
  const [assigned, setAssigned] = useState(device.gatewayAssigned)
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
          gatewayId: assigned && gateway ? gateway.id : null,
        }),
      }),
    onSuccess: () => {
      toastOk('Reader saved.')
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
        <Label>Gym PC</Label>
        {gateway ? (
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={assigned} onChange={(e) => setAssigned(e.target.checked)} />
            <span>Connected to {gateway.name}</span>
          </label>
        ) : (
          <p className="text-sm text-muted">Add the gym PC first.</p>
        )}
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
    onSuccess: () => toastOk(action === 'OPEN' ? 'Open sent to the reader.' : 'Close sent to the reader.'),
  })
  return (
    <Card className="max-w-lg space-y-3">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-muted">Remote door</h2>
      <p className="text-sm text-muted">Opens or closes the door. It is sent to the reader; this screen does not show that the lock moved.</p>
      <Select value={action} onChange={(e) => setAction(e.target.value)}>
        <option value="OPEN">Open</option>
        <option value="CLOSE">Close</option>
      </Select>
      <Textarea rows={2} placeholder="Reason" value={reason} onChange={(e) => setReason(e.target.value)} />
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
        I confirm this door command
      </label>
      <Button
        variant="danger"
        disabled={!confirmed || !reason.trim() || door.isPending}
        onClick={() => door.mutate()}
      >
        {door.isPending ? 'Sending…' : action === 'OPEN' ? 'Open the door' : 'Close the door'}
      </Button>
      {door.isSuccess ? (
        <p className="text-sm text-ok">Sent to the reader. Wait to see if the door moves.</p>
      ) : null}
      {door.error ? <QueryError error={door.error} /> : null}
    </Card>
  )
}
