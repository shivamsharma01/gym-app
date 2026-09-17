import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { MemberPicker } from '@/components/MemberPicker'
import { Button, Card, Input, Label, PageHeader, Select, Textarea } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { NOTIFICATION_CHANNELS, NOTIFICATION_TEMPLATE_KEYS } from '@/lib/catalog'
import { cn, formatDateTime } from '@/lib/cn'
import type { Member, PageResponse } from '@/lib/types'
import { Link } from 'react-router'

type Template = {
  id: string
  templateKey: string
  channel: string
  subject: string | null
  body: string
}

type Outbound = {
  id: string
  channel: string
  templateKey: string | null
  recipient: string
  status: string
  attemptCount: number
  lastError: string | null

  membershipId: number | null
  announcementId: number | null

  whatsappTemplateName: string | null
  whatsappLanguage: string | null

  sentAt: string | null
  deliveredAt: string | null
  readAt: string | null
  createdAt: string
}


type Announcement = {
  id: string
  title: string
  body: string
  published: boolean
  createdAt: string
}

type Membership = {
  id: string
  planName: string
  startDate: string
  endDate: string
  status: string
}

export function NotificationsPage() {
  const qc = useQueryClient()

  const [member, setMember] = useState<Member | null>(null)
  const [membershipId, setMembershipId] = useState('')
  const [templateRef, setTemplateRef] = useState('EXPIRY_REMINDER_3_DAYS|WHATSAPP')

  /*
   * Load outbound notifications.
   */
  const outbound = useQuery({
    queryKey: ['notifications'],
    queryFn: () => api<PageResponse<Outbound>>('/api/v1/notifications?size=30'),
  })

  /*
   * Load notification templates.
   */
  const templates = useQuery({
    queryKey: ['notification-templates'],
    queryFn: () => api<Template[]>('/api/v1/notification-templates'),
  })

  /*
   * Load memberships after a member is selected.
   *
   * The selected membership belongs to the selected member.
   */
  const memberships = useQuery({
    queryKey: ['member-memberships', member?.id],
    queryFn: () =>
      api<Membership[]>(
        `/api/v1/members/${encodeURIComponent(member!.id)}/memberships`,
      ),
    enabled: !!member,
  })

  /*
   * When the selected member changes, the old membership
   * must not remain selected.
   */
  useEffect(() => {
    setMembershipId('')
  }, [member?.id])

  /*
   * Send notification manually from the UI.
   */
  const send = useMutation({
    mutationFn: () => {
      const [templateKey, channel] = templateRef.split('|')

      return api<Outbound>('/api/v1/notifications', {
        method: 'POST',
        body: JSON.stringify({
          memberId: member?.id,
          membershipId,
          templateKey,
          channel,
        }),
      })
    },

    onSuccess: () => {
      void qc.invalidateQueries({
        queryKey: ['notifications'],
      })
    },
  })

  /*
   * Manually run expiry reminders.
   *
   * This is still tenant-specific because this request
   * comes from the currently logged-in staff/admin user.
   */
  const reminders = useMutation({
    mutationFn: () =>
      api<{ queued: number }>('/api/v1/notifications/expiry-reminders', {
        method: 'POST',
      }),

    onSuccess: () => {
      void qc.invalidateQueries({
        queryKey: ['notifications'],
      })
    },
  })

  const canSend =
    !!member &&
    !!membershipId &&
    !send.isPending

  return (
    <div className="space-y-8">
      <PageHeader
        title="Notifications"
        description="Delivery is a mock adapter: rows are marked SENT without an email/SMS vendor."
      />

      <p className="text-sm">
        <Link
          className="underline"
          to="/app/notifications/templates"
        >
          Templates
        </Link>
        {' · '}
        <Link
          className="underline"
          to="/app/announcements"
        >
          Announcements
        </Link>
      </p>

      <Card className="space-y-4">
        <h2 className="text-sm font-semibold uppercase text-muted">
          Send to a member
        </h2>

        {/* Member selection */}
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

        {/* Membership selection */}
        {member ? (
          <div className="space-y-2">
            <Label>Membership</Label>

            {memberships.isLoading ? (
              <p className="text-sm text-muted">
                Loading memberships...
              </p>
            ) : memberships.error ? (
              <QueryError error={memberships.error} />
            ) : memberships.data && memberships.data.length > 0 ? (
              <Select
                value={membershipId}
                onChange={(e) => setMembershipId(e.target.value)}
              >
                <option value="">
                  Select membership
                </option>

                {memberships.data.map((membership) => (
                  <option
                    key={membership.id}
                    value={membership.id}
                  >
                    {membership.planName} · {membership.startDate} →{' '}
                    {membership.endDate} · {membership.status}
                  </option>
                ))}
              </Select>
            ) : (
              <p className="text-sm text-muted">
                This member has no memberships.
              </p>
            )}
          </div>
        ) : null}

        {/* Template selection */}
        <div className="space-y-2">
          <Label>Template</Label>

          <Select
            value={templateRef}
            onChange={(e) => setTemplateRef(e.target.value)}
          >
            {templates.data && templates.data.length > 0
              ? templates.data.map((template) => (
                <option
                  key={`${template.templateKey}|${template.channel}`}
                  value={`${template.templateKey}|${template.channel}`}
                >
                  {template.templateKey} ({template.channel})
                </option>
              ))
              : (
                <>
                  {NOTIFICATION_TEMPLATE_KEYS.map((key) =>
                    NOTIFICATION_CHANNELS.map((channel) => (
                      <option
                        key={`${key}|${channel}`}
                        value={`${key}|${channel}`}
                      >
                        {key} ({channel})
                      </option>
                    )),
                  )}
                </>
              )}
          </Select>
        </div>

        {/* Selected membership information */}
        {membershipId ? (
          <div className="rounded-lg border border-line bg-raised p-3 text-sm">
            {(() => {
              const selectedMembership = memberships.data?.find(
                (membership) => membership.id === membershipId,
              )

              if (!selectedMembership) {
                return null
              }

              return (
                <div className="space-y-1">
                  <div>
                    <span className="font-medium">
                      Plan:
                    </span>{' '}
                    {selectedMembership.planName}
                  </div>

                  <div>
                    <span className="font-medium">
                      Start:
                    </span>{' '}
                    {selectedMembership.startDate}
                  </div>

                  <div>
                    <span className="font-medium">
                      Expiry:
                    </span>{' '}
                    {selectedMembership.endDate}
                  </div>

                  <div>
                    <span className="font-medium">
                      Status:
                    </span>{' '}
                    {selectedMembership.status}
                  </div>
                </div>
              )
            })()}
          </div>
        ) : null}

        {send.error ? (
          <QueryError error={send.error} />
        ) : null}

        {/* Send notification */}
        <Button
          disabled={!canSend}
          onClick={() => send.mutate()}
        >
          {send.isPending ? 'Sending...' : 'Queue send'}
        </Button>

        {/* Manual expiry reminder trigger */}
        <Button
          variant="outline"
          disabled={reminders.isPending}
          onClick={() => reminders.mutate()}
        >
          {reminders.isPending
            ? 'Running...'
            : 'Run expiry reminders'}
        </Button>

        {reminders.error ? (
          <QueryError error={reminders.error} />
        ) : null}
      </Card>

      {/* Notification history */}
      <Card className="space-y-4">
        <div>
          <h2 className="text-sm font-semibold uppercase text-muted">
            Notification History
          </h2>

          <p className="mt-1 text-sm text-muted">
            WhatsApp notification delivery history.
          </p>
        </div>

        {outbound.isLoading ? (
          <p className="text-sm text-muted">
            Loading notification history...
          </p>
        ) : outbound.error ? (
          <QueryError error={outbound.error} />
        ) : !outbound.data?.content?.length ? (
          <p className="text-sm text-muted">
            No notifications have been sent yet.
          </p>
        ) : (
          <div className="overflow-x-auto rounded-xl border border-line">
            <table className="w-full text-sm">
              <thead className="border-b border-line bg-raised">
                <tr>
                  <th className="px-4 py-3 text-left font-semibold">
                    Type
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    Recipient
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    Channel
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    WhatsApp Template
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    Status
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    Attempts
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    Error
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    Sent
                  </th>
                </tr>
              </thead>

              <tbody className="divide-y divide-line">
                {outbound.data.content.map((row) => (
                  <tr
                    key={row.id}
                    className="hover:bg-raised"
                  >
                    {/* Type */}
                    <td className="px-4 py-3">
                      <div className="font-medium">
                        {row.templateKey ?? '—'}
                      </div>

                      {row.membershipId !== null ? (
                        <div className="text-xs text-muted">
                          Membership #{row.membershipId}
                        </div>
                      ) : null}

                      {row.announcementId !== null ? (
                        <div className="text-xs text-muted">
                          Announcement #{row.announcementId}
                        </div>
                      ) : null}
                    </td>

                    {/* Recipient */}
                    <td className="px-4 py-3 whitespace-nowrap">
                      {row.recipient}
                    </td>

                    {/* Channel */}
                    <td className="px-4 py-3">
                      {row.channel}
                    </td>

                    {/* WhatsApp template */}
                    <td className="px-4 py-3">
                      {row.whatsappTemplateName ? (
                        <div>
                          <div className="font-medium">
                            {row.whatsappTemplateName}
                          </div>

                          <div className="text-xs text-muted">
                            {row.whatsappLanguage ?? '—'}
                          </div>
                        </div>
                      ) : (
                        '—'
                      )}
                    </td>

                    {/* Status */}
                    <td className="px-4 py-3">
                      <span
                        className={
                          row.status === 'SENT'
                            ? 'font-medium text-green-600'
                            : row.status === 'FAILED'
                              ? 'font-medium text-red-600'
                              : row.status === 'QUEUED'
                                ? 'font-medium text-yellow-600'
                                : 'font-medium text-muted'
                        }
                      >
                        {row.status}
                      </span>
                    </td>

                    {/* Attempts */}
                    <td className="px-4 py-3">
                      {row.attemptCount}
                    </td>

                    {/* Error */}
                    <td className="max-w-xs px-4 py-3">
                      {row.lastError ? (
                        <span
                          className="block truncate text-xs text-red-600"
                          title={row.lastError}
                        >
                          {row.lastError}
                        </span>
                      ) : (
                        <span className="text-muted">
                          —
                        </span>
                      )}
                    </td>

                    {/* Sent */}
                    <td className="px-4 py-3 whitespace-nowrap text-muted">
                      {formatDateTime(
                        row.sentAt ?? row.createdAt,
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>


    </div>
  )
}

export function NotificationTemplatesPage() {
  const qc = useQueryClient()

  const templates = useQuery({
    queryKey: ['notification-templates'],
    queryFn: () => api<Template[]>('/api/v1/notification-templates'),
  })

  const templateKeys = [
    'MEMBERSHIP_CREATED',
    'MEMBERSHIP_RENEWED',
    'MEMBERSHIP_FROZEN',
    'MEMBERSHIP_UNFROZEN',
    'MEMBERSHIP_CANCELLED',
    'MEMBERSHIP_DATES_UPDATED',
    'EXPIRY_REMINDER_3_DAYS',
    'MEMBERSHIP_EXPIRED',
  ]

  const [templateKey, setTemplateKey] = useState('MEMBERSHIP_CREATED')
  const [subject, setSubject] = useState('')
  const [body, setBody] = useState(
    'Hi {{memberName}}, your {{membershipPlan}} membership has been created at {{gymName}}. It is valid from {{startDate}} to {{expiryDate}}.',
  )

  const selectedTemplate = templates.data?.find(
    (t) =>
      t.templateKey === templateKey &&
      t.channel === 'WHATSAPP',
  )

  const isConfigured = Boolean(selectedTemplate)

  function selectTemplate(key: string) {
    setTemplateKey(key)

    const existing = templates.data?.find(
      (t) =>
        t.templateKey === key &&
        t.channel === 'WHATSAPP',
    )

    if (existing) {
      setSubject(existing.subject ?? '')
      setBody(existing.body)
    } else {
      setSubject('')
      setBody(defaultBodyFor(key))
    }
  }

  const save = useMutation({
    mutationFn: () =>
      api('/api/v1/notification-templates', {
        method: 'PUT',
        body: JSON.stringify({
          templateKey,
          channel: 'WHATSAPP',
          subject,
          body,
        }),
      }),

    onSuccess: () => {
      void qc.invalidateQueries({
        queryKey: ['notification-templates'],
      })
    },
  })

  return (
    <div className="max-w-3xl space-y-6">
      <Link
        to="/app/notifications"
        className="inline-flex items-center text-sm font-medium text-muted hover:text-ink hover:underline"
      >
        ← Back to Notifications
      </Link>
      <PageHeader
        title="Notification Templates"
        description="Configure the WhatsApp messages used by membership events and expiry reminders."
      />

      <Card className="space-y-5">
        <div>
          <h2 className="text-sm font-semibold uppercase text-muted">
            Membership notification template
          </h2>

          <p className="mt-1 text-sm text-muted">
            Select a notification event to configure its WhatsApp message.
          </p>
        </div>

        <div>
          <Label>Notification event</Label>

          <div className="mt-1 flex items-center gap-3">
            <Select
              value={templateKey}
              onChange={(e) => selectTemplate(e.target.value)}
              className="flex-1"
            >
              {templateKeys.map((key) => {
                const configured = templates.data?.some(
                  (t) =>
                    t.templateKey === key &&
                    t.channel === 'WHATSAPP',
                )

                return (
                  <option key={key} value={key}>
                    {key}
                  </option>
                )
              })}
            </Select>

            <span
              className={`whitespace-nowrap rounded-full px-3 py-1 text-xs font-medium ${isConfigured
                ? 'bg-green-100 text-green-700'
                : 'bg-yellow-100 text-yellow-700'
                }`}
            >
              {isConfigured ? 'Configured' : 'Not configured'}
            </span>
          </div>
        </div>

        <div>
          <Label>Channel</Label>

          <Input
            value="WHATSAPP"
            readOnly
            className="bg-raised"
          />
        </div>

        <div>
          <Label>Subject</Label>

          <Input
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            placeholder="Optional"
          />
        </div>

        <div>
          <Label>Body</Label>

          <Textarea
            rows={6}
            value={body}
            onChange={(e) => setBody(e.target.value)}
            placeholder="WhatsApp message body"
          />
        </div>

        <div className="rounded-lg border border-line bg-raised p-4">
          <p className="mb-3 text-xs font-semibold uppercase text-muted">
            Available variables
          </p>

          <div className="flex flex-wrap gap-2">
            {[
              '{{memberName}}',
              '{{memberCode}}',
              '{{gymName}}',
              '{{membershipPlan}}',
              '{{startDate}}',
              '{{expiryDate}}',
              '{{daysRemaining}}',
              '{{amount}}',
              '{{currency}}',
              '{{amountPaid}}',
              '{{membershipStatus}}',
            ].map((variable) => (
              <code
                key={variable}
                className="rounded bg-panel px-2 py-1 text-xs"
              >
                {variable}
              </code>
            ))}
          </div>
        </div>

        {save.error ? <QueryError error={save.error} /> : null}

        <Button
          disabled={save.isPending}
          onClick={() => save.mutate()}
        >
          {save.isPending ? 'Saving...' : 'Save WhatsApp template'}
        </Button>
      </Card>
    </div>
  )
}

function defaultBodyFor(templateKey: string): string {
  switch (templateKey) {
    case 'MEMBERSHIP_CREATED':
      return 'Hi {{memberName}}, your {{membershipPlan}} membership has been created at {{gymName}}. It is valid from {{startDate}} to {{expiryDate}}.'

    case 'MEMBERSHIP_RENEWED':
      return 'Hi {{memberName}}, your {{membershipPlan}} membership has been renewed at {{gymName}}. It is valid from {{startDate}} to {{expiryDate}}.'

    case 'MEMBERSHIP_FROZEN':
      return 'Hi {{memberName}}, your {{membershipPlan}} membership at {{gymName}} has been frozen.'

    case 'MEMBERSHIP_UNFROZEN':
      return 'Hi {{memberName}}, your {{membershipPlan}} membership at {{gymName}} has been unfrozen. Your membership is valid until {{expiryDate}}.'

    case 'MEMBERSHIP_CANCELLED':
      return 'Hi {{memberName}}, your {{membershipPlan}} membership at {{gymName}} has been cancelled.'

    case 'MEMBERSHIP_DATES_UPDATED':
      return 'Hi {{memberName}}, your membership dates have been updated. Your membership is valid from {{startDate}} to {{expiryDate}}.'

    case 'EXPIRY_REMINDER_3_DAYS':
      return 'Hi {{memberName}}, your {{membershipPlan}} membership at {{gymName}} expires on {{expiryDate}}, which is in {{daysRemaining}} days.'

    case 'MEMBERSHIP_EXPIRED':
      return 'Hi {{memberName}}, your {{membershipPlan}} membership at {{gymName}} expired on {{expiryDate}}.'

    default:
      return 'Hi {{memberName}}, this is a notification from {{gymName}}.'
  }
}





// export function AnnouncementsPage() {
//   const qc = useQueryClient()

//   const list = useQuery({
//     queryKey: ['announcements'],
//     queryFn: () =>
//       api<Announcement[]>('/api/v1/announcements'),
//   })

//   const [title, setTitle] = useState('')
//   const [body, setBody] = useState('')

//   const create = useMutation({
//     mutationFn: () =>
//       api('/api/v1/announcements', {
//         method: 'POST',
//         body: JSON.stringify({
//           title,
//           body,
//           published: true,
//         }),
//       }),

//     onSuccess: () => {
//       setTitle('')
//       setBody('')

//       void qc.invalidateQueries({
//         queryKey: ['announcements'],
//       })

//       void qc.invalidateQueries({
//         queryKey: ['notifications'],
//       })
//     },
//   })

//   return (
//     <div className="max-w-3xl space-y-6">
//       <Link
//         to="/app/notifications"
//         className="inline-flex items-center text-sm font-medium text-muted hover:text-ink hover:underline"
//       >
//         ← Back to Notifications
//       </Link>
//       <PageHeader
//         title="Announcements"
//         description="Send an announcement to all members with an active membership."
//       />

//       <Card className="space-y-5">
//         <div>
//           <h2 className="text-sm font-semibold uppercase text-muted">
//             Create announcement
//           </h2>

//           <p className="mt-1 text-sm text-muted">
//             The announcement will be sent through WhatsApp to all active members.
//           </p>
//         </div>

//         <div>
//           <Label>Title</Label>

//           <Input
//             placeholder="Gym Holiday Notice"
//             value={title}
//             onChange={(e) => setTitle(e.target.value)}
//           />
//         </div>

//         <div>
//           <Label>Message</Label>

//           <Textarea
//             rows={7}
//             placeholder={`Hi {{memberName}},

// Our gym will be closed tomorrow for maintenance.

// Thank you,
// {{gymName}}`}
//             value={body}
//             onChange={(e) => setBody(e.target.value)}
//           />
//         </div>

//         <div className="rounded-lg border border-line bg-raised p-4">
//           <p className="mb-3 text-xs font-semibold uppercase text-muted">
//             Available variables
//           </p>

//           <div className="flex flex-wrap gap-2">
//             {[
//               '{{memberName}}',
//               '{{memberCode}}',
//               '{{gymName}}',
//             ].map((variable) => (
//               <code
//                 key={variable}
//                 className="rounded bg-panel px-2 py-1 text-xs"
//               >
//                 {variable}
//               </code>
//             ))}
//           </div>
//         </div>

//         <div className="rounded-lg border border-line bg-raised p-3">
//           <div className="text-sm font-medium">
//             Channel
//           </div>

//           <div className="mt-1 text-sm text-muted">
//             WHATSAPP
//           </div>
//         </div>

//         {create.error ? (
//           <QueryError error={create.error} />
//         ) : null}

//         <Button
//           disabled={
//             !title.trim() ||
//             !body.trim() ||
//             create.isPending
//           }
//           onClick={() => create.mutate()}
//         >
//           {create.isPending
//             ? 'Publishing...'
//             : 'Publish & Send WhatsApp'}
//         </Button>
//       </Card>

//       <div>
//         <h2 className="mb-3 text-sm font-semibold uppercase text-muted">
//           Previous announcements
//         </h2>

//         <ul className="space-y-3">
//           {list.data?.map((announcement) => (
//             <li
//               key={announcement.id}
//               className="rounded-xl border border-line p-4"
//             >
//               <div className="flex items-start justify-between gap-4">
//                 <div>
//                   <div className="font-semibold">
//                     {announcement.title}
//                   </div>

//                   <p className="mt-2 whitespace-pre-wrap text-sm text-muted">
//                     {announcement.body}
//                   </p>
//                 </div>

//                 <span className="whitespace-nowrap rounded-full bg-green-100 px-2 py-1 text-xs font-medium text-green-700">
//                   WHATSAPP
//                 </span>
//               </div>
//             </li>
//           ))}
//         </ul>
//       </div>
//     </div>
//   )
// }

export function AnnouncementsPage() {
  const qc = useQueryClient()

  const list = useQuery({
    queryKey: ['announcements'],
    queryFn: () => api<Announcement[]>('/api/v1/announcements'),
  })

  const outbound = useQuery({
    queryKey: ['notifications', 'announcements'],
    queryFn: () => api<PageResponse<Outbound>>('/api/v1/notifications?size=100'),
  })

  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')

  const create = useMutation({
    mutationFn: () =>
      api('/api/v1/announcements', {
        method: 'POST',
        body: JSON.stringify({
          title,
          body,
          published: true,
        }),
      }),

    onSuccess: () => {
      setTitle('')
      setBody('')

      void qc.invalidateQueries({
        queryKey: ['announcements'],
      })

      void qc.invalidateQueries({
        queryKey: ['notifications', 'announcements'],
      })
    },
  })

  const announcementNotifications =
    outbound.data?.content.filter(
      (row) => row.announcementId !== null,
    ) ?? []

  return (
    <div className="max-w-3xl space-y-6">
      <Link
        to="/app/notifications"
        className="inline-flex items-center text-sm font-medium text-muted hover:text-ink hover:underline"
      >
        ← Back to Notifications
      </Link>

      <PageHeader
        title="Announcements"
        description="Send an announcement to all members with an active membership."
      />

      {/* Create Announcement */}
      <Card className="space-y-5">
        <div>
          <h2 className="text-sm font-semibold uppercase text-muted">
            Create announcement
          </h2>

          <p className="mt-1 text-sm text-muted">
            The announcement will be sent through WhatsApp to all active
            members.
          </p>
        </div>

        <div>
          <Label>Title</Label>

          <Input
            placeholder="Gym Holiday Notice"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
        </div>

        <div>
          <Label>Message</Label>

          <Textarea
            rows={7}
            placeholder={`Hi {{memberName}},

Our gym will be closed tomorrow for maintenance.

Thank you,
{{gymName}}`}
            value={body}
            onChange={(e) => setBody(e.target.value)}
          />
        </div>

        {/* Variables */}
        <div className="rounded-lg border border-line bg-raised p-4">
          <p className="mb-3 text-xs font-semibold uppercase text-muted">
            Available variables
          </p>

          <div className="flex flex-wrap gap-2">
            {[
              '{{memberName}}',
              '{{memberCode}}',
              '{{gymName}}',
            ].map((variable) => (
              <code
                key={variable}
                className="rounded bg-panel px-2 py-1 text-xs"
              >
                {variable}
              </code>
            ))}
          </div>
        </div>

        {/* Channel */}
        <div className="rounded-lg border border-line bg-raised p-3">
          <div className="text-sm font-medium">
            Channel
          </div>

          <div className="mt-1 text-sm text-muted">
            WHATSAPP
          </div>
        </div>

        {create.error ? (
          <QueryError error={create.error} />
        ) : null}

        <Button
          disabled={
            !title.trim() ||
            !body.trim() ||
            create.isPending
          }
          onClick={() => create.mutate()}
        >
          {create.isPending
            ? 'Publishing...'
            : 'Publish & Send WhatsApp'}
        </Button>
      </Card>

      {/* Previous announcements */}
      <div>
        <h2 className="mb-3 text-sm font-semibold uppercase text-muted">
          Previous announcements
        </h2>

        {list.isLoading ? (
          <p className="text-sm text-muted">
            Loading announcements...
          </p>
        ) : list.error ? (
          <QueryError error={list.error} />
        ) : list.data?.length === 0 ? (
          <p className="text-sm text-muted">
            No announcements yet.
          </p>
        ) : (
          <ul className="space-y-3">
            {list.data?.map((announcement) => (
              <li
                key={announcement.id}
                className="rounded-xl border border-line p-4"
              >
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <div className="font-semibold">
                      {announcement.title}
                    </div>

                    <p className="mt-2 whitespace-pre-wrap text-sm text-muted">
                      {announcement.body}
                    </p>

                    <div className="mt-2 text-xs text-muted">
                      {announcement.published
                        ? 'Published'
                        : 'Draft'}{' '}
                      · {formatDateTime(announcement.createdAt)}
                    </div>
                  </div>

                  <span className="whitespace-nowrap rounded-full bg-green-100 px-2 py-1 text-xs font-medium text-green-700">
                    WHATSAPP
                  </span>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Announcement Delivery History */}
      <Card className="space-y-4">
        <div>
          <h2 className="text-sm font-semibold uppercase text-muted">
            Announcement History
          </h2>

          <p className="mt-1 text-sm text-muted">
            WhatsApp delivery history for all announcements.
          </p>
        </div>

        {outbound.isLoading ? (
          <p className="text-sm text-muted">
            Loading delivery history...
          </p>
        ) : outbound.error ? (
          <QueryError error={outbound.error} />
        ) : announcementNotifications.length === 0 ? (
          <p className="text-sm text-muted">
            No announcement messages have been sent yet.
          </p>
        ) : (
          <div className="overflow-x-auto rounded-xl border border-line">
            <table className="w-full text-sm">
              <thead className="border-b border-line bg-raised">
                <tr>
                  <th className="px-4 py-3 text-left font-semibold">
                    Announcement
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    Recipient
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    Channel
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    Template
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    Status
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    Attempts
                  </th>

                  <th className="px-4 py-3 text-left font-semibold">
                    Sent
                  </th>
                </tr>
              </thead>

              <tbody className="divide-y divide-line">
                {announcementNotifications.map((row) => (
                  <tr
                    key={row.id}
                    className="hover:bg-raised"
                  >
                    <td className="px-4 py-3">
                      <div className="font-medium">
                        Announcement #{row.announcementId}
                      </div>
                    </td>

                    <td className="px-4 py-3">
                      {row.recipient}
                    </td>

                    <td className="px-4 py-3">
                      {row.channel}
                    </td>

                    <td className="px-4 py-3">
                      {row.whatsappTemplateName ? (
                        <div>
                          <div className="font-medium">
                            {row.whatsappTemplateName}
                          </div>

                          <div className="text-xs text-muted">
                            {row.whatsappLanguage ?? '—'}
                          </div>
                        </div>
                      ) : (
                        '—'
                      )}
                    </td>

                    <td className="px-4 py-3">
                      <span
                        className={
                          row.status === 'SENT'
                            ? 'font-medium text-green-600'
                            : row.status === 'FAILED'
                              ? 'font-medium text-red-600'
                              : row.status === 'QUEUED'
                                ? 'font-medium text-amber-600'
                                : 'font-medium text-muted'
                        }
                      >
                        {row.status}
                      </span>

                      {row.status === 'FAILED' &&
                        row.lastError ? (
                        <div
                          className="mt-1 max-w-xs truncate text-xs text-red-500"
                          title={row.lastError}
                        >
                          {row.lastError}
                        </div>
                      ) : null}
                    </td>

                    <td className="px-4 py-3">
                      {row.attemptCount}
                    </td>

                    <td className="px-4 py-3 text-muted">
                      {formatDateTime(
                        row.sentAt ?? row.createdAt,
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  )
}

function notificationSource(row: Outbound) {
  if (row.announcementId !== null) {
    return 'Announcement'
  }

  if (row.membershipId !== null) {
    return 'Membership'
  }

  return 'Manual'
}

function StatusBadge({ status }: { status: string }) {
  const normalized = status.toUpperCase()

  const className =
    normalized === 'SENT'
      ? 'bg-green-100 text-green-700'
      : normalized === 'FAILED'
        ? 'bg-red-100 text-red-700'
        : normalized === 'QUEUED'
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

