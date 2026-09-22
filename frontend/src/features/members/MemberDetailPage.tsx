import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { ConfirmDialog } from '@/components/Dialog'
import { QueryError } from '@/components/QueryError'
import { Badge, Button, Card, EmptyState, Label, PageHeader, SectionTitle, Select, Skeleton } from '@/components/ui'
import { MembershipPanel } from '@/features/memberships/MembershipPanel'
import { ApiError, api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { formatDate, formatDateTime, money } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { AccessStatus, Attendance, Device, Mapping, Member, Membership, PageResponse, Payment } from '@/lib/types'

export function MemberDetailPage() {
  const { id } = useParams()
  const { has } = useAuth()
  const navigate = useNavigate()
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
  const attendance = useQuery({
    queryKey: ['member-attendance', id],
    queryFn: () => api<PageResponse<Attendance>>(`/api/v1/members/${id}/attendance?page=0&size=20`),
    enabled: has('ATTENDANCE_VIEW'),
  })
  const payments = useQuery({
    queryKey: ['member-payments', id],
    queryFn: () => api<Payment[]>(`/api/v1/members/${id}/payments`),
    enabled: has('PAYMENT_VIEW'),
  })
  const [confirmDeactivate, setConfirmDeactivate] = useState(false)
  const deactivate = useMutation({
    mutationFn: () => api(`/api/v1/members/${id}`, { method: 'DELETE' }),
    onSuccess: () => navigate('/app/members'),
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
          </div>
        }
      />
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
          <MembershipPanel memberId={m.id} rows={memberships.data ?? []} />
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
            <Card padded={false} className="divide-y divide-line overflow-hidden">
              {payments.data.map((p) => (
                <div key={p.id} className="flex justify-between gap-3 px-4 py-3 text-sm">
                  <span>
                    {money(p.amount, p.currency)} · {p.method}
                  </span>
                  <Badge tone={statusTone(p.status)}>{p.status}</Badge>
                </div>
              ))}
            </Card>
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
          )}
        </section>
      ) : null}

      {has('DEVICE_MANAGE') ? <EnrollmentPanel memberId={m.id} memberCode={m.memberCode} /> : null}

      <ConfirmDialog
        open={confirmDeactivate}
        onClose={() => setConfirmDeactivate(false)}
        title="Deactivate this member?"
        description="History is kept. They will lose door access once memberships and device sync catch up."
        confirmLabel="Deactivate"
        danger
        busy={deactivate.isPending}
        onConfirm={() => deactivate.mutate()}
      />
    </div>
  )
}

function EnrollmentPanel({ memberId, memberCode }: { memberId: string; memberCode: string }) {
  const devices = useQuery({
    queryKey: ['devices'],
    queryFn: () => api<PageResponse<Device>>('/api/v1/devices?size=50'),
  })
  const [deviceId, setDeviceId] = useState('')
  const map = useMutation({
    mutationFn: () =>
      api<Mapping>(`/api/v1/devices/${deviceId}/mappings`, {
        method: 'POST',
        body: JSON.stringify({ memberId, deviceUserId: memberCode }),
      }),
  })
  const registered = devices.data?.content ?? []
  return (
    <section>
      <SectionTitle title="Device enrolment" />
      <Card className="space-y-3">
        <p className="text-sm text-muted">
          Powering on a tablet does not fill this list. Register the terminal under Devices first (name, entrance/exit,
          LAN IP, gateway). Mapping then queues a face user on that tablet using this member’s gym code (
          {memberCode}) as the device user id — that is the id TrueFace stores, not Staff/Admin.
        </p>
        {registered.length === 0 ? (
          <p className="text-sm text-muted">
            No devices in the app yet.{' '}
            <Link className="underline" to="/app/devices">
              Register a device
            </Link>
            .
          </p>
        ) : (
          <>
            <Label>Device</Label>
            <Select value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
              <option value="">Select device</option>
              {registered.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name}
                </option>
              ))}
            </Select>
            <Button disabled={!deviceId || map.isPending} onClick={() => map.mutate()}>
              Map to device
            </Button>
          </>
        )}
        {map.isSuccess ? (
          <p className="text-sm text-ok">
            Mapped as device user {memberCode}. Enrolment {map.data.enrollmentStatus}, sync {map.data.syncState}. This is
            not proof the face is on the terminal — complete the face on the device, then check enrolment there.
          </p>
        ) : null}
        {map.error instanceof ApiError ? <p className="text-sm text-danger">{map.error.message}</p> : null}
      </Card>
    </section>
  )
}
