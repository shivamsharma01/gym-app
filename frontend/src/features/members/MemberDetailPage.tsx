import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { ConfirmDialog } from '@/components/Dialog'
import { PageNav, Pager } from '@/components/Pager'
import { QueryError } from '@/components/QueryError'
import { Badge, Button, Card, EmptyState, PageHeader, SectionTitle, Skeleton } from '@/components/ui'
import { useMemberPhotoUrl } from '@/features/members/MemberPhotoField'
import { MembershipPanel } from '@/features/memberships/MembershipPanel'
import { ApiError, api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { formatDate, formatDateTime, money } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import { usePagedRows } from '@/lib/usePagedRows'
import type { AccessStatus, Attendance, Member, MemberDeviceSync, Membership, PageResponse, Payment } from '@/lib/types'

const ATTENDANCE_PAGE_SIZE = 20

export function MemberDetailPage() {
  const { id } = useParams()
  const { has } = useAuth()
  const qc = useQueryClient()
  const member = useQuery({ queryKey: ['member', id], queryFn: () => api<Member>(`/api/v1/members/${id}`) })
  const memberships = useQuery({
    queryKey: ['memberships', id],
    queryFn: () => api<Membership[]>(`/api/v1/members/${id}/memberships`),
    enabled: has('MEMBERSHIP_VIEW'),
  })
  const access = useQuery({
    queryKey: ['access', id],
    queryFn: () => api<AccessStatus>(`/api/v1/members/${id}/access`),
  })
  const [attendancePage, setAttendancePage] = useState(0)
  const attendance = useQuery({
    queryKey: ['member-attendance', id, attendancePage],
    queryFn: () =>
      api<PageResponse<Attendance>>(`/api/v1/members/${id}/attendance?page=${attendancePage}&size=${ATTENDANCE_PAGE_SIZE}`),
    enabled: has('ATTENDANCE_VIEW'),
    placeholderData: keepPreviousData,
  })
  const payments = useQuery({
    queryKey: ['member-payments', id],
    queryFn: () => api<Payment[]>(`/api/v1/members/${id}/payments`),
    enabled: has('PAYMENT_VIEW'),
  })
  const paymentRows = usePagedRows(payments.data ?? [], 10)
  const photo = useMemberPhotoUrl(id)
  const sync = useQuery({
    queryKey: ['member-device-sync', id],
    queryFn: () => api<MemberDeviceSync>(`/api/v1/members/${id}/device-sync`),
  })
  const location = useLocation()
  const photoError = (location.state as { photoError?: string | null } | null)?.photoError ?? null
  const [confirmDeactivate, setConfirmDeactivate] = useState(false)
  const [confirmReactivate, setConfirmReactivate] = useState(false)
  const deactivate = useMutation({
    mutationFn: () => api(`/api/v1/members/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      void member.refetch()
      void access.refetch()
      void qc.invalidateQueries({ queryKey: ['members'] })
      void qc.invalidateQueries({ queryKey: ['sync-commands'] })
      setConfirmDeactivate(false)
    },
  })
  const reactivate = useMutation({
    mutationFn: () => api<Member>(`/api/v1/members/${id}/reactivate`, { method: 'POST' }),
    onSuccess: () => {
      void member.refetch()
      void access.refetch()
      void qc.invalidateQueries({ queryKey: ['members'] })
      void qc.invalidateQueries({ queryKey: ['sync-commands'] })
      setConfirmReactivate(false)
    },
  })

  if (member.isLoading) return <Skeleton className="h-40" />
  if (member.error || !member.data) return <QueryError error={member.error ?? new Error('Member not found')} />
  const m = member.data

  return (
    <div className="space-y-8">
      <PageHeader
        title={m.fullName}
        description={`${m.memberCode} · joined ${formatDate(m.joinedOn)}`}
        actions={
          <div className="flex gap-2">
            {has('MEMBER_UPDATE') ? (
              <Link to={`/app/members/${m.id}/edit`}>
                <Button variant="outline">Edit</Button>
              </Link>
            ) : null}
            {has('MEMBER_DELETE') && m.status === 'ACTIVE' ? (
              <Button variant="danger" onClick={() => setConfirmDeactivate(true)}>
                Deactivate
              </Button>
            ) : null}
            {has('MEMBER_DELETE') && m.status === 'INACTIVE' ? (
              <Button onClick={() => setConfirmReactivate(true)}>Reactivate</Button>
            ) : null}
          </div>
        }
      />
      {photoError ? (
        <p className="rounded-lg border border-line px-4 py-3 text-sm text-danger">
          Member saved, but the photo was not: {photoError}. Open Edit to try another photo.
        </p>
      ) : null}
      <div className="flex items-center gap-4">
        <div className="flex h-24 w-24 shrink-0 items-center justify-center overflow-hidden rounded-lg border border-line bg-raised text-xs text-muted">
          {photo.url ? <img src={photo.url} alt={m.fullName} className="h-full w-full object-cover" /> : 'No photo'}
        </div>
        <div className="text-sm text-muted">
          {sync.data?.face
            ? sync.data.face.source === 'DEVICE'
              ? `Photo taken on ${sync.data.face.sourceDeviceName ?? 'a device'} · ${formatDateTime(sync.data.face.changedAt)}`
              : `Photo added in the app · ${formatDateTime(sync.data.face.changedAt)}`
            : 'No photo yet. Add one with Edit, or enrol the face on any device.'}
        </div>
      </div>
      <div className="flex flex-wrap gap-2">
        <Badge tone={statusTone(m.status)}>{m.status}</Badge>
        <Badge tone={m.creationSource === 'DEVICE_IMPORT' ? 'warn' : 'ok'}>
          {m.creationSource === 'DEVICE_IMPORT' ? 'Created from device' : 'Created manually'}
        </Badge>
        {access.data ? (
          <Badge tone={access.data.allowed ? 'ok' : 'danger'}>
            {access.data.allowed ? 'Access allowed' : `Denied: ${access.data.reason}`}
          </Badge>
        ) : null}
      </div>

      <section>
        <SectionTitle title="Overview" />
        <Card className="grid gap-4 text-sm sm:grid-cols-2">
          <div>
            <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">Email</div>
            <div className="mt-1">{m.email ?? '—'}</div>
          </div>
          <div>
            <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">Phone</div>
            <div className="mt-1">{m.phone ?? '—'}</div>
          </div>
          <div>
            <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">Date of birth</div>
            <div className="mt-1">{formatDate(m.dateOfBirth)}</div>
          </div>
          <div>
            <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">Gender</div>
            <div className="mt-1">{m.gender}</div>
          </div>
          <div className="sm:col-span-2">
            Notes
            <br />
            <span className="text-muted">{m.notes || '—'}</span>
          </div>
        </Card>
      </section>

      {has('MEMBERSHIP_VIEW') ? (
        <section>
          <SectionTitle title="Membership" />
          {memberships.error ? <QueryError error={memberships.error} /> : null}
          <MembershipPanel memberId={m.id} memberStatus={m.status} rows={memberships.data ?? []} />
        </section>
      ) : null}

      {has('PAYMENT_VIEW') ? (
        <section>
          <SectionTitle title="Payments" />
          {!payments.data?.length ? (
            <EmptyState
              title="No payments"
              body="This list is history only. Record cash/UPI/card on Payments, pick this member, and link the membership."
            />
          ) : (
            <>
              <Card padded={false} className="divide-y divide-line overflow-hidden">
                {paymentRows.pageRows.map((p) => (
                  <div key={p.id} className="flex justify-between gap-3 px-4 py-3 text-sm">
                    <span>
                      {money(p.amount, p.currency)} · {p.method}
                    </span>
                    <Badge tone={statusTone(p.status)}>{p.status}</Badge>
                  </div>
                ))}
              </Card>
              <Pager {...paymentRows.pager} />
            </>
          )}
          {has('PAYMENT_CREATE') ? (
            <Link to="/app/payments" className="mt-3 inline-block text-sm font-medium text-accent hover:underline">
              Record a payment
            </Link>
          ) : null}
        </section>
      ) : null}

      {has('ATTENDANCE_VIEW') ? (
        <section>
          <SectionTitle title="Attendance" />
          {!attendance.data?.content.length ? (
            <p className="text-sm text-muted">No attendance for this member yet.</p>
          ) : (
            <>
              <Card padded={false} className="divide-y divide-line overflow-hidden">
                {attendance.data.content.map((row) => (
                  <div key={row.id} className="flex justify-between gap-3 px-4 py-3 text-sm">
                    <span>{formatDateTime(row.occurredAt)}</span>
                    <span className="text-muted">
                      {row.result} · {row.method}
                    </span>
                  </div>
                ))}
              </Card>
              <PageNav data={attendance.data} onPageChange={setAttendancePage} />
            </>
          )}
        </section>
      ) : null}

      <DeviceSyncPanel memberId={m.id} />

      <ConfirmDialog
        open={confirmDeactivate}
        onClose={() => setConfirmDeactivate(false)}
        title="Deactivate this member?"
        description="They lose app access immediately. Mapped devices get disable commands queued (check each device Sync tab until confirmed)."
        confirmLabel="Deactivate"
        danger
        busy={deactivate.isPending}
        onConfirm={() => deactivate.mutate()}
      />
      <ConfirmDialog
        open={confirmReactivate}
        onClose={() => setConfirmReactivate(false)}
        title="Reactivate this member?"
        description="Restores the member to ACTIVE and queues device enable/sync for mapped terminals. Check device Sync tabs for pending work."
        confirmLabel="Reactivate"
        busy={reactivate.isPending}
        onConfirm={() => reactivate.mutate()}
      />
    </div>
  )
}

const COMMAND_LABELS: Record<string, string> = {
  CREATE_USER: 'Add user',
  UPDATE_USER: 'Update name',
  UPDATE_VALIDITY: 'Update access dates',
  DISABLE_USER: 'Block access',
  ENABLE_USER: 'Allow access',
  REMOVE_USER: 'Remove user',
  UPSERT_FACE: 'Send photo',
  DELETE_FACE: 'Remove photo',
  REPORT_DEVICE_USER: 'Read photo from device',
}

function faceStateLabel(row: MemberDeviceSync['devices'][number], face: MemberDeviceSync['face']) {
  if (!face) return { label: 'No photo', tone: 'muted' as const }
  if (row.faceSyncState === 'SYNCED' && row.faceVersionSynced === face.version) {
    return { label: 'Photo on device', tone: 'ok' as const }
  }
  if (row.faceSyncState === 'FAILED') return { label: 'Photo failed', tone: 'danger' as const }
  return { label: 'Photo waiting', tone: 'warn' as const }
}

function DeviceSyncPanel({ memberId }: { memberId: string }) {
  const { has } = useAuth()
  const sync = useQuery({
    queryKey: ['member-device-sync', memberId],
    queryFn: () => api<MemberDeviceSync>(`/api/v1/members/${memberId}/device-sync`),
  })
  const retry = useMutation({
    mutationFn: (deviceId: string) =>
      api(`/api/v1/members/${memberId}/device-sync/${deviceId}/retry`, { method: 'POST' }),
    onSuccess: () => void sync.refetch(),
  })
  if (sync.error) return <QueryError error={sync.error} />
  const data = sync.data
  return (
    <section>
      <SectionTitle title="Device sync" />
      <Card className="space-y-4">
        <p className="text-sm text-muted">
          Saved on the server first, then sent to every device that has a gateway. A device shows “waiting” until it
          confirms. Members and photos added on a device come back here and go to the other devices too.
        </p>
        {!data ? (
          <Skeleton className="h-16" />
        ) : data.devices.length === 0 ? (
          <p className="text-sm text-muted">
            No devices with a gateway yet.{' '}
            <Link className="underline" to="/app/devices">
              Set up a device
            </Link>
            .
          </p>
        ) : (
          <div className="divide-y divide-line">
            {data.devices.map((row) => {
              const face = faceStateLabel(row, data.face)
              return (
                <div key={row.deviceId} className="flex flex-wrap items-start justify-between gap-3 py-3 text-sm">
                  <div className="space-y-1">
                    <div className="font-medium">
                      {row.deviceName}{' '}
                      <span className="text-xs text-muted">· device user {row.deviceUserId}</span>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Badge tone={statusTone(row.connectionState)}>{row.connectionState ?? 'UNKNOWN'}</Badge>
                      <Badge tone={statusTone(row.userSyncState)}>
                        {row.userSyncState === 'SYNCED' ? 'Details on device' : `Details ${row.userSyncState ?? 'pending'}`}
                      </Badge>
                      <Badge tone={face.tone}>{face.label}</Badge>
                    </div>
                    {row.openCommands.length ? (
                      <div className="text-xs text-muted">
                        Waiting:{' '}
                        {row.openCommands
                          .map((c) => `${COMMAND_LABELS[c.type] ?? c.type}${c.attemptCount > 1 ? ` (try ${c.attemptCount})` : ''}`)
                          .join(', ')}
                      </div>
                    ) : null}
                    {row.faceLastError ? <div className="text-xs text-danger">{row.faceLastError}</div> : null}
                  </div>
                  {has('DEVICE_SYNC') ? (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={retry.isPending}
                      onClick={() => retry.mutate(row.deviceId)}
                    >
                      Send again
                    </Button>
                  ) : null}
                </div>
              )
            })}
          </div>
        )}
        {retry.error instanceof ApiError ? <p className="text-sm text-danger">{retry.error.message}</p> : null}
      </Card>
    </section>
  )
}
