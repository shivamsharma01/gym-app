import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { MemberPicker } from '@/components/MemberPicker'
import {
  Button,
  Card,
  Label,
  PageHeader,
  Select,
} from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import {
  NOTIFICATION_CHANNELS,
  NOTIFICATION_TEMPLATE_KEYS,
} from '@/lib/catalog'
import type { Member, PageResponse } from '@/lib/types'
import { Link } from 'react-router'

type Template = {
  id: string
  templateKey: string
  channel: string
  subject: string | null
  body: string | null
  whatsappTemplateName?: string | null
  whatsappLanguage?: string | null
  active?: boolean
}

type Outbound = {
  id: string
  channel: string
  templateKey: string | null
  recipient: string
  status: string
  attemptCount: number
  lastError: string | null

  memberId?: number | null
  membershipId: number | null
  announcementId: number | null

  whatsappTemplateName: string | null
  whatsappLanguage: string | null

  scheduledAt?: string | null
  sentAt: string | null
  deliveredAt: string | null
  readAt: string | null
  createdAt: string
}

type Membership = {
  id: string
  planName: string
  startDate: string
  endDate: string
  status: string
}

type WhatsAppConfiguration = {
  configured: boolean
  enabled?: boolean
}

export function NotificationsPage() {
  const qc = useQueryClient()

  const [member, setMember] = useState<Member | null>(null)
  const [membershipId, setMembershipId] = useState('')
  const [templateRef, setTemplateRef] = useState(
    'EXPIRY_REMINDER_3_DAYS|WHATSAPP',
  )

  /*
   * Notification history
   */
  const outbound = useQuery({
    queryKey: ['notifications'],
    queryFn: () =>
      api<PageResponse<Outbound>>(
        '/api/v1/notifications?size=100',
      ),
  })

  /*
   * Application notification templates
   */
  const templates = useQuery({
    queryKey: ['notification-templates'],
    queryFn: () =>
      api<Template[]>(
        '/api/v1/notification-templates',
      ),
  })

  /*
   * Memberships belonging to selected member
   */
  const memberships = useQuery({
    queryKey: ['member-memberships', member?.id],
    queryFn: () =>
      api<Membership[]>(
        `/api/v1/members/${encodeURIComponent(
          member!.id,
        )}/memberships`,
      ),
    enabled: !!member,
  })

  /*
   * Dashboard counts
   */
  const statistics = useMemo(() => {
    const rows = outbound.data?.content ?? []

    const sent = rows.filter(
      (row) => row.status === 'SENT',
    ).length

    const failed = rows.filter(
      (row) => row.status === 'FAILED',
    ).length

    const queued = rows.filter(
      (row) =>
        row.status === 'QUEUED' ||
        row.status === 'PENDING',
    ).length

    return {
      total: rows.length,
      sent,
      failed,
      queued,
    }
  }, [outbound.data])

  /*
   * Send notification manually
   */
  const send = useMutation({
    mutationFn: () => {
      const [templateKey, channel] =
        templateRef.split('|')

      return api<Outbound>(
        '/api/v1/notifications',
        {
          method: 'POST',
          body: JSON.stringify({
            memberId: member?.id,
            membershipId,
            templateKey,
            channel,
          }),
        },
      )
    },

    onSuccess: () => {
      void qc.invalidateQueries({
        queryKey: ['notifications'],
      })
    },
  })

  const whatsappConfig = useQuery({
    queryKey: ['whatsapp-config'],
    queryFn: () =>
      api<WhatsAppConfiguration>(
        '/api/v1/whatsapp/configuration',
      ),
  })

  const whatsappConfigured =
    whatsappConfig.data?.configured === true

  const canSend =
    whatsappConfigured &&
    !!member &&
    !!membershipId &&
    !!templateRef &&
    !send.isPending


  return (
    <div className="space-y-8">
      <PageHeader
        title="Notifications"
        description="Monitor WhatsApp notification delivery and send notifications manually."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Link
              to="/app/notifications"
              className="rounded-md bg-raised px-3 py-2 text-sm font-medium text-ink"
            >
              Notifications
            </Link>

            <Link
              to="/app/notifications/templates"
              className="rounded-md px-3 py-2 text-sm font-medium text-muted hover:bg-raised hover:text-ink"
            >
              Templates
            </Link>

            <Link
              to="/app/settings/whatsapp"
              className="rounded-md px-3 py-2 text-sm font-medium text-muted hover:bg-raised hover:text-ink"
            >
              WhatsApp Configuration
            </Link>

            <Link
              to="/app/notifications/history"
              className="rounded-md px-3 py-2 text-sm font-medium text-muted hover:bg-raised hover:text-ink"
            >
              History
            </Link>

            <Button
              variant="outline"
              disabled={outbound.isFetching}
              onClick={() => {
                void outbound.refetch()
              }}
            >
              {outbound.isFetching ? 'Refreshing...' : 'Refresh'}
            </Button>
          </div>
        }
      />



      {/* ============================================================
          STATISTICS
         ============================================================ */}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="Total"
          value={statistics.total}
          description="Notifications loaded"
        />

        <StatCard
          label="Sent"
          value={statistics.sent}
          description="Successfully sent"
          color="green"
        />

        <StatCard
          label="Failed"
          value={statistics.failed}
          description="Delivery failed"
          color="red"
        />

        <StatCard
          label="Pending"
          value={statistics.queued}
          description="Waiting to be processed"
          color="yellow"
        />
      </div>

      {/* ============================================================
          MANUAL SEND
         ============================================================ */}

      <Card className="space-y-5">
        <div>
          <h2 className="text-sm font-semibold uppercase text-muted">
            Send notification
          </h2>

          <p className="mt-1 text-sm text-muted">
            Queue a WhatsApp notification for a member.
          </p>
        </div>

        {/* Member */}
        <div className="space-y-2">
          <Label>Member</Label>

          <MemberPicker
            value={member}
            onChange={(selectedMember) => {
              setMember(selectedMember)
              setMembershipId('')
            }}
          />
        </div>

        {/* Membership */}
        {member ? (
          <div className="space-y-2">
            <Label>Membership</Label>

            {memberships.isLoading ? (
              <p className="text-sm text-muted">
                Loading memberships...
              </p>
            ) : memberships.error ? (
              <QueryError error={memberships.error} />
            ) : memberships.data &&
              memberships.data.length > 0 ? (
              <Select
                value={membershipId}
                onChange={(event) =>
                  setMembershipId(
                    event.target.value,
                  )
                }
              >
                <option value="">
                  Select membership
                </option>

                {memberships.data.map(
                  (membership) => (
                    <option
                      key={membership.id}
                      value={membership.id}
                    >
                      {membership.planName} ·{' '}
                      {membership.startDate} →{' '}
                      {membership.endDate} ·{' '}
                      {membership.status}
                    </option>
                  ),
                )}
              </Select>
            ) : (
              <p className="text-sm text-muted">
                This member has no memberships.
              </p>
            )}
          </div>
        ) : null}

        {/* Template */}
        <div className="space-y-2">
          <Label>Notification template</Label>

          <Select
            value={templateRef}
            onChange={(event) =>
              setTemplateRef(event.target.value)
            }
          >
            {templates.data &&
              templates.data.length > 0 ? (
              templates.data
                .filter(
                  (template) =>
                    template.channel ===
                    'WHATSAPP' &&
                    template.active !== false,
                )
                .map((template) => (
                  <option
                    key={`${template.templateKey}|${template.channel}`}
                    value={`${template.templateKey}|${template.channel}`}
                  >
                    {template.templateKey}
                    {template.whatsappTemplateName
                      ? ` · ${template.whatsappTemplateName}`
                      : ''}
                  </option>
                ))
            ) : (
              <>
                {NOTIFICATION_TEMPLATE_KEYS.map(
                  (key) =>
                    NOTIFICATION_CHANNELS.map(
                      (channel) => (
                        <option
                          key={`${key}|${channel}`}
                          value={`${key}|${channel}`}
                        >
                          {key} ({channel})
                        </option>
                      ),
                    ),
                )}
              </>
            )}
          </Select>
        </div>

        {/* Membership preview */}
        {membershipId ? (
          <MembershipPreview
            memberships={memberships.data}
            membershipId={membershipId}
          />
        ) : null}

        {send.error ? (
          <QueryError error={send.error} />
        ) : null}

        {whatsappConfig.isLoading ? (
          <div className="rounded-lg border border-line bg-raised p-3 text-sm text-muted">
            Checking WhatsApp configuration...
          </div>
        ) : whatsappConfig.error ? (
          <QueryError error={whatsappConfig.error} />
        ) : !whatsappConfigured ? (
          <div className="rounded-lg border border-yellow-200 bg-yellow-50 p-4">
            <div className="font-medium text-yellow-800">
              WhatsApp is not configured
            </div>

            <p className="mt-1 text-sm text-yellow-700">
              Configure WhatsApp before sending notifications.
            </p>

            <Link
              to="/app/settings/whatsapp"
              className="mt-3 inline-flex text-sm font-medium text-yellow-800 underline hover:no-underline"
            >
              Configure WhatsApp
            </Link>
          </div>
        ) : null}

        <Button
          disabled={!canSend}
          onClick={() => send.mutate()}
        >
          {send.isPending
            ? 'Queueing...'
            : 'Queue notification'}
        </Button>
      </Card>
    </div>
  )
}

/* ================================================================
   STAT CARD
   ================================================================ */

function StatCard({
  label,
  value,
  description,
  color = 'gray',
}: {
  label: string
  value: number
  description: string
  color?: 'gray' | 'green' | 'red' | 'yellow'
}) {
  const colors = {
    gray: 'text-ink',
    green: 'text-green-600',
    red: 'text-red-600',
    yellow: 'text-yellow-600',
  }

  return (
    <Card>
      <div className="text-sm text-muted">
        {label}
      </div>

      <div
        className={`mt-2 text-3xl font-bold ${colors[color]}`}
      >
        {value}
      </div>

      <div className="mt-1 text-xs text-muted">
        {description}
      </div>
    </Card>
  )
}

/* ================================================================
   MEMBERSHIP PREVIEW
   ================================================================ */

function MembershipPreview({
  memberships,
  membershipId,
}: {
  memberships?: Membership[]
  membershipId: string
}) {
  const membership = memberships?.find(
    (item) => item.id === membershipId,
  )

  if (!membership) {
    return null
  }

  return (
    <div className="rounded-lg border border-line bg-raised p-4 text-sm">
      <div className="grid gap-2 sm:grid-cols-2">
        <div>
          <span className="font-medium">
            Plan:
          </span>{' '}
          {membership.planName}
        </div>

        <div>
          <span className="font-medium">
            Status:
          </span>{' '}
          {membership.status}
        </div>

        <div>
          <span className="font-medium">
            Start:
          </span>{' '}
          {membership.startDate}
        </div>

        <div>
          <span className="font-medium">
            Expiry:
          </span>{' '}
          {membership.endDate}
        </div>
      </div>
    </div>
  )
}

/* ================================================================
   STATUS BADGE
   ================================================================ */

function StatusBadge({
  status,
}: {
  status: string
}) {
  const normalized = status.toUpperCase()

  const className =
    normalized === 'SENT'
      ? 'bg-green-100 text-green-700'
      : normalized === 'FAILED'
        ? 'bg-red-100 text-red-700'
        : normalized === 'QUEUED' ||
          normalized === 'PENDING'
          ? 'bg-yellow-100 text-yellow-700'
          : 'bg-gray-100 text-gray-700'

  return (
    <span
      className={`inline-flex rounded-full px-2.5 py-1 text-xs font-semibold ${className}`}
    >
      {normalized}
    </span>
  )
}
