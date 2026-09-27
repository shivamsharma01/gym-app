import { useQuery } from '@tanstack/react-query'
import { Card, PageHeader } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/cn'
import type { PageResponse } from '@/lib/types'
import { Link } from 'react-router'

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

export function NotificationHistoryPage() {
    const outbound = useQuery({
        queryKey: ['notifications', 'history'],
        queryFn: () =>
            api<PageResponse<Outbound>>(
                '/api/v1/notifications?size=100',
            ),
    })

    return (
        <div className="space-y-6">
            <PageHeader
                title="Notification History"
                description="View WhatsApp notification delivery history, status, attempts and errors."
                actions={
                    <div className="flex flex-wrap items-center gap-2">
                        <Link
                            to="/app/notifications"
                            className="rounded-md px-3 py-2 text-sm font-medium text-muted hover:bg-raised hover:text-ink"
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
                            className="rounded-md bg-raised px-3 py-2 text-sm font-medium text-ink"
                        >
                            History
                        </Link>
                    </div>
                }
            />

            <Card className="space-y-4">
                <div>
                    <h2 className="text-sm font-semibold uppercase text-muted">
                        Delivery History
                    </h2>

                    <p className="mt-1 text-sm text-muted">
                        All notification delivery attempts for this gym.
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
                        No notifications have been queued yet.
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
                                        Template
                                    </th>

                                    <th className="px-4 py-3 text-left font-semibold">
                                        Status
                                    </th>

                                    <th className="px-4 py-3 text-left font-semibold">
                                        Attempts
                                    </th>

                                    <th className="px-4 py-3 text-left font-semibold">
                                        Scheduled
                                    </th>

                                    <th className="px-4 py-3 text-left font-semibold">
                                        Sent
                                    </th>

                                    <th className="px-4 py-3 text-left font-semibold">
                                        Error
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

                                        {/* Template */}
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
                                            <StatusBadge status={row.status} />
                                        </td>

                                        {/* Attempts */}
                                        <td className="px-4 py-3">
                                            {row.attemptCount}
                                        </td>

                                        {/* Scheduled */}
                                        <td className="px-4 py-3 whitespace-nowrap text-muted">
                                            {row.scheduledAt
                                                ? formatDateTime(row.scheduledAt)
                                                : '—'}
                                        </td>

                                        {/* Sent */}
                                        <td className="px-4 py-3 whitespace-nowrap text-muted">
                                            {row.sentAt
                                                ? formatDateTime(row.sentAt)
                                                : '—'}
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
