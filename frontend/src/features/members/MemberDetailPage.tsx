import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { Badge, Button, Card, EmptyState, Input, Label, PageHeader, Select, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
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
              <Button
                variant="danger"
                onClick={() => {
                  if (confirm('Deactivate this member? History is kept.')) deactivate.mutate()
                }}
              >
                Deactivate
              </Button>
            ) : null}
          </div>
        }
      />
      <div className="flex flex-wrap gap-2">
        <Badge tone={statusTone(m.status)}>{m.status}</Badge>
        {access.data ? (
          <Badge tone={access.data.allowed ? 'ok' : 'danger'}>
            {access.data.allowed ? 'Access allowed' : `Denied: ${access.data.reason}`}
          </Badge>
        ) : null}
      </div>

      <section>
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-muted">Overview</h2>
        <Card className="grid gap-3 text-sm sm:grid-cols-2">
          <div>
            Email
            <br />
            <span className="text-muted">{m.email ?? '—'}</span>
          </div>
          <div>
            Phone
            <br />
            <span className="text-muted">{m.phone ?? '—'}</span>
          </div>
          <div>
            Date of birth
            <br />
            <span className="text-muted">{formatDate(m.dateOfBirth)}</span>
          </div>
          <div>
            Gender
            <br />
            <span className="text-muted">{m.gender}</span>
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
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-muted">Membership</h2>
          {memberships.error ? <QueryError error={memberships.error} /> : null}
          <MembershipPanel memberId={m.id} rows={memberships.data ?? []} />
        </section>
      ) : null}

      {has('PAYMENT_VIEW') ? (
        <section>
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-muted">Payments</h2>
          {!payments.data?.length ? (
            <EmptyState title="No payments" body="Record a payment from the payments page." />
          ) : (
            <ul className="divide-y divide-line rounded-xl border border-line">
              {payments.data.map((p) => (
                <li key={p.id} className="flex justify-between px-4 py-3 text-sm">
                  <span>
                    {money(p.amount, p.currency)} · {p.method}
                  </span>
                  <Badge tone={statusTone(p.status)}>{p.status}</Badge>
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}

      {has('ATTENDANCE_VIEW') ? (
        <section>
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-muted">Attendance</h2>
          {!attendance.data?.content.length ? (
            <p className="text-sm text-muted">No attendance for this member yet.</p>
          ) : (
            <ul className="divide-y divide-line rounded-xl border border-line">
              {attendance.data.content.map((row) => (
                <li key={row.id} className="flex justify-between px-4 py-3 text-sm">
                  <span>{formatDateTime(row.occurredAt)}</span>
                  <span className="text-muted">
                    {row.result} · {row.method}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}

      {has('DEVICE_MANAGE') ? <EnrollmentPanel memberId={m.id} /> : null}
    </div>
  )
}

function EnrollmentPanel({ memberId }: { memberId: string }) {
  const devices = useQuery({
    queryKey: ['devices'],
    queryFn: () => api<PageResponse<Device>>('/api/v1/devices?size=50'),
  })
  const [deviceId, setDeviceId] = useState('')
  const [deviceUserId, setDeviceUserId] = useState('')
  const map = useMutation({
    mutationFn: () =>
      api<Mapping>(`/api/v1/devices/${deviceId}/mappings`, {
        method: 'POST',
        body: JSON.stringify({ memberId, deviceUserId }),
      }),
  })
  return (
    <section>
      <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-muted">Device enrolment</h2>
      <Card className="space-y-3">
        <p className="text-sm text-muted">
          Remote face capture is not claimed as success. Mapping a user queues CREATE_USER; complete the face on the
          terminal, then verify enrolment status from the device.
        </p>
        <Label>Device</Label>
        <Select value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
          <option value="">Select device</option>
          {devices.data?.content.map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
        </Select>
        <Label>Device user id</Label>
        <Input value={deviceUserId} onChange={(e) => setDeviceUserId(e.target.value)} placeholder="e.g. 1001" />
        <Button disabled={!deviceId || !deviceUserId || map.isPending} onClick={() => map.mutate()}>
          Map to device
        </Button>
        {map.isSuccess ? (
          <p className="text-sm text-ok">
            Mapped. Enrolment {map.data.enrollmentStatus}, sync {map.data.syncState}. This is not proof the face is on
            the terminal.
          </p>
        ) : null}
        {map.error instanceof ApiError ? <p className="text-sm text-danger">{map.error.message}</p> : null}
      </Card>
    </section>
  )
}
