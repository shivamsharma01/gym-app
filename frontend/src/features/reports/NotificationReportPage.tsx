import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Button,
  Card,
  Input,
  Label,
  PageHeader,
  Select,
} from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { Link } from 'react-router'

type Summary = {
  total: number
  sent: number
  delivered: number
  read: number
  failed: number
  pending: number
}

type Rates = {
  deliveryRate: number
  readRate: number
  failureRate: number
}

type TemplateSummary = {
  templateKey: string
  total: number
  sent: number
  delivered: number
  read: number
  failed: number
  pending: number
}

type DailySummary = {
  date: string
  total: number
  sent: number
  delivered: number
  read: number
  failed: number
  pending: number
}

type NotificationRow = {
  id: string
  memberId: number | null
  membershipId: number | null
  recipient: string
  channel: string
  templateKey: string | null
  whatsappTemplateName: string | null
  whatsappLanguage: string | null
  status: string
  attemptCount: number
  lastError: string | null
  scheduledAt: string | null
  sentAt: string | null
  deliveredAt: string | null
  readAt: string | null
  createdAt: string
}

type NotificationReportResponse = {
  summary: Summary
  rates: Rates
  byTemplate: TemplateSummary[]
  daily: DailySummary[]
  notifications: NotificationRow[]
}

const STATUS_OPTIONS = [
  'ALL',
  'QUEUED',
  'SENT',
  'DELIVERED',
  'READ',
  'FAILED',
]

function formatDateTime(value: string | null) {
  if (!value) {
    return '—'
  }

  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function today() {
  return new Date().toISOString().slice(0, 10)
}

function daysAgo(days: number) {
  const date = new Date()
  date.setDate(date.getDate() - days)

  return date.toISOString().slice(0, 10)
}

export function NotificationReportPage() {
  const [from, setFrom] =
    useState(daysAgo(30))

  const [to, setTo] =
    useState(today())

  const [status, setStatus] =
    useState('ALL')

  const [templateKey, setTemplateKey] =
    useState('ALL')

  const [applied, setApplied] = useState({
    from: daysAgo(30),
    to: today(),
    status: 'ALL',
    templateKey: 'ALL',
  })

  const query = useQuery({
    queryKey: [
      'notification-report',
      applied,
    ],

    queryFn: () => {
      const params =
        new URLSearchParams({
          from: applied.from,
          to: applied.to,
          status: applied.status,
          templateKey:
            applied.templateKey,
        })

      return api<NotificationReportResponse>(
        `/api/v1/reports/notifications?${params.toString()}`,
      )
    },
  })

  const templateOptions =
    useMemo(() => {
      return query.data?.byTemplate ?? []
    }, [query.data])

  function applyFilters() {
    setApplied({
      from,
      to,
      status,
      templateKey,
    })
  }

  function resetFilters() {
    const defaultFrom = daysAgo(30)
    const defaultTo = today()

    setFrom(defaultFrom)
    setTo(defaultTo)
    setStatus('ALL')
    setTemplateKey('ALL')

    setApplied({
      from: defaultFrom,
      to: defaultTo,
      status: 'ALL',
      templateKey: 'ALL',
    })
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Notification Report"
        description="Review WhatsApp notification volume, delivery, read and failure activity."
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
              className="rounded-md px-3 py-2 text-sm font-medium text-muted hover:bg-raised hover:text-ink"
            >
              History
            </Link>
          </div>
        }
      />

      {/* FILTERS */}

      <Card className="space-y-5">
        <div>
          <h2 className="text-sm font-semibold uppercase text-muted">
            Report filters
          </h2>

          <p className="mt-1 text-sm text-muted">
            Select the period and notification
            criteria for this report.
          </p>
        </div>

        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          <div>
            <Label>From</Label>

            <Input
              className="mt-1"
              type="date"
              value={from}
              onChange={(event) =>
                setFrom(event.target.value)
              }
            />
          </div>

          <div>
            <Label>To</Label>

            <Input
              className="mt-1"
              type="date"
              value={to}
              onChange={(event) =>
                setTo(event.target.value)
              }
            />
          </div>

          <div>
            <Label>Status</Label>

            <Select
              className="mt-1"
              value={status}
              onChange={(event) =>
                setStatus(event.target.value)
              }
            >
              {STATUS_OPTIONS.map((item) => (
                <option
                  key={item}
                  value={item}
                >
                  {item}
                </option>
              ))}
            </Select>
          </div>

          <div>
            <Label>Template</Label>

            <Select
              className="mt-1"
              value={templateKey}
              onChange={(event) =>
                setTemplateKey(
                  event.target.value,
                )
              }
            >
              <option value="ALL">
                All templates
              </option>

              {templateOptions.map(
                (template) => (
                  <option
                    key={template.templateKey}
                    value={
                      template.templateKey
                    }
                  >
                    {template.templateKey}
                  </option>
                ),
              )}
            </Select>
          </div>
        </div>

        <div className="flex flex-wrap gap-3">
          <Button
            disabled={
              query.isFetching
            }
            onClick={applyFilters}
          >
            {query.isFetching
              ? 'Loading...'
              : 'Apply filters'}
          </Button>

          <Button
            variant="outline"
            onClick={resetFilters}
          >
            Reset
          </Button>
        </div>
      </Card>

      {query.isLoading ? (
        <Card>
          <p className="text-sm text-muted">
            Loading notification report...
          </p>
        </Card>
      ) : query.error ? (
        <QueryError error={query.error} />
      ) : query.data ? (
        <>
          <SummarySection
            summary={query.data.summary}
            rates={query.data.rates}
          />

          <TemplateSection
            templates={
              query.data.byTemplate
            }
          />

          <DailySection
            daily={query.data.daily}
          />

          <NotificationTable
            rows={query.data.notifications}
          />
        </>
      ) : null}
    </div>
  )
}

/* ================================================================
   SUMMARY
   ================================================================ */

function SummarySection({
  summary,
  rates,
}: {
  summary: Summary
  rates: Rates
}) {
  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
        <StatCard
          label="Total"
          value={summary.total}
          description="Notifications"
        />

        <StatCard
          label="Sent"
          value={summary.sent}
          description="Sent successfully"
          color="green"
        />

        <StatCard
          label="Delivered"
          value={summary.delivered}
          description="Delivered"
          color="blue"
        />

        <StatCard
          label="Read"
          value={summary.read}
          description="Read by recipient"
          color="purple"
        />

        <StatCard
          label="Failed"
          value={summary.failed}
          description="Failed"
          color="red"
        />

        <StatCard
          label="Pending"
          value={summary.pending}
          description="Waiting"
          color="yellow"
        />
      </div>

      <div className="grid gap-4 md:grid-cols-3">
        <RateCard
          label="Delivery rate"
          value={rates.deliveryRate}
          description="Delivered ÷ sent"
        />

        <RateCard
          label="Read rate"
          value={rates.readRate}
          description="Read ÷ delivered"
        />

        <RateCard
          label="Failure rate"
          value={rates.failureRate}
          description="Failed ÷ total"
        />
      </div>
    </div>
  )
}

function StatCard({
  label,
  value,
  description,
  color = 'gray',
}: {
  label: string
  value: number
  description: string
  color?:
    | 'gray'
    | 'green'
    | 'blue'
    | 'purple'
    | 'red'
    | 'yellow'
}) {
  const colors = {
    gray: 'text-ink',
    green: 'text-green-600',
    blue: 'text-blue-600',
    purple: 'text-purple-600',
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

function RateCard({
  label,
  value,
  description,
}: {
  label: string
  value: number
  description: string
}) {
  return (
    <Card>
      <div className="text-sm font-medium">
        {label}
      </div>

      <div className="mt-2 text-3xl font-bold text-ink">
        {value.toFixed(2)}%
      </div>

      <div className="mt-1 text-xs text-muted">
        {description}
      </div>
    </Card>
  )
}

/* ================================================================
   TEMPLATE REPORT
   ================================================================ */

function TemplateSection({
  templates,
}: {
  templates: TemplateSummary[]
}) {
  return (
    <Card className="space-y-4">
      <div>
        <h2 className="text-sm font-semibold uppercase text-muted">
          Notification performance by template
        </h2>

        <p className="mt-1 text-sm text-muted">
          Delivery activity grouped by application
          notification template.
        </p>
      </div>

      {!templates.length ? (
        <p className="text-sm text-muted">
          No template activity for the selected
          period.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-line">
          <table className="w-full text-sm">
            <thead className="border-b border-line bg-raised">
              <tr>
                <th className="px-4 py-3 text-left">
                  Template
                </th>

                <th className="px-4 py-3 text-left">
                  Total
                </th>

                <th className="px-4 py-3 text-left">
                  Sent
                </th>

                <th className="px-4 py-3 text-left">
                  Delivered
                </th>

                <th className="px-4 py-3 text-left">
                  Read
                </th>

                <th className="px-4 py-3 text-left">
                  Failed
                </th>

                <th className="px-4 py-3 text-left">
                  Pending
                </th>
              </tr>
            </thead>

            <tbody className="divide-y divide-line">
              {templates.map(
                (template) => (
                  <tr
                    key={
                      template.templateKey
                    }
                    className="hover:bg-raised"
                  >
                    <td className="px-4 py-3 font-medium">
                      {template.templateKey}
                    </td>

                    <td className="px-4 py-3">
                      {template.total}
                    </td>

                    <td className="px-4 py-3">
                      {template.sent}
                    </td>

                    <td className="px-4 py-3">
                      {template.delivered}
                    </td>

                    <td className="px-4 py-3">
                      {template.read}
                    </td>

                    <td className="px-4 py-3 text-red-600">
                      {template.failed}
                    </td>

                    <td className="px-4 py-3 text-yellow-600">
                      {template.pending}
                    </td>
                  </tr>
                ),
              )}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}

/* ================================================================
   DAILY
   ================================================================ */

function DailySection({
  daily,
}: {
  daily: DailySummary[]
}) {
  return (
    <Card className="space-y-4">
      <div>
        <h2 className="text-sm font-semibold uppercase text-muted">
          Daily notification activity
        </h2>

        <p className="mt-1 text-sm text-muted">
          Notification activity grouped by creation
          date.
        </p>
      </div>

      {!daily.length ? (
        <p className="text-sm text-muted">
          No daily activity for the selected
          period.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-line">
          <table className="w-full text-sm">
            <thead className="border-b border-line bg-raised">
              <tr>
                <th className="px-4 py-3 text-left">
                  Date
                </th>

                <th className="px-4 py-3 text-left">
                  Total
                </th>

                <th className="px-4 py-3 text-left">
                  Sent
                </th>

                <th className="px-4 py-3 text-left">
                  Delivered
                </th>

                <th className="px-4 py-3 text-left">
                  Read
                </th>

                <th className="px-4 py-3 text-left">
                  Failed
                </th>

                <th className="px-4 py-3 text-left">
                  Pending
                </th>
              </tr>
            </thead>

            <tbody className="divide-y divide-line">
              {daily.map((item) => (
                <tr
                  key={item.date}
                  className="hover:bg-raised"
                >
                  <td className="px-4 py-3 font-medium">
                    {item.date}
                  </td>

                  <td className="px-4 py-3">
                    {item.total}
                  </td>

                  <td className="px-4 py-3">
                    {item.sent}
                  </td>

                  <td className="px-4 py-3">
                    {item.delivered}
                  </td>

                  <td className="px-4 py-3">
                    {item.read}
                  </td>

                  <td className="px-4 py-3 text-red-600">
                    {item.failed}
                  </td>

                  <td className="px-4 py-3 text-yellow-600">
                    {item.pending}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}

/* ================================================================
   DETAIL TABLE
   ================================================================ */

function NotificationTable({
  rows,
}: {
  rows: NotificationRow[]
}) {
  return (
    <Card className="space-y-4">
      <div>
        <h2 className="text-sm font-semibold uppercase text-muted">
          Notification details
        </h2>

        <p className="mt-1 text-sm text-muted">
          Individual notification records for the
          selected filters.
        </p>
      </div>

      {!rows.length ? (
        <p className="text-sm text-muted">
          No notifications found.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-line">
          <table className="w-full text-sm">
            <thead className="border-b border-line bg-raised">
              <tr>
                <th className="px-4 py-3 text-left">
                  Date
                </th>

                <th className="px-4 py-3 text-left">
                  Recipient
                </th>

                <th className="px-4 py-3 text-left">
                  Event
                </th>

                <th className="px-4 py-3 text-left">
                  Template
                </th>

                <th className="px-4 py-3 text-left">
                  Status
                </th>

                <th className="px-4 py-3 text-left">
                  Attempts
                </th>

                <th className="px-4 py-3 text-left">
                  Sent
                </th>

                <th className="px-4 py-3 text-left">
                  Delivered
                </th>

                <th className="px-4 py-3 text-left">
                  Read
                </th>

                <th className="px-4 py-3 text-left">
                  Error
                </th>
              </tr>
            </thead>

            <tbody className="divide-y divide-line">
              {rows.map((row) => (
                <tr
                  key={row.id}
                  className="hover:bg-raised"
                >
                  <td className="px-4 py-3 whitespace-nowrap text-muted">
                    {formatDateTime(
                      row.createdAt,
                    )}
                  </td>

                  <td className="px-4 py-3 whitespace-nowrap">
                    {row.recipient}
                  </td>

                  <td className="px-4 py-3">
                    {row.templateKey ?? '—'}
                  </td>

                  <td className="px-4 py-3">
                    {row.whatsappTemplateName ? (
                      <>
                        <div className="font-medium">
                          {
                            row.whatsappTemplateName
                          }
                        </div>

                        <div className="text-xs text-muted">
                          {row.whatsappLanguage ??
                            'en_US'}
                        </div>
                      </>
                    ) : (
                      '—'
                    )}
                  </td>

                  <td className="px-4 py-3">
                    <StatusBadge
                      status={row.status}
                    />
                  </td>

                  <td className="px-4 py-3">
                    {row.attemptCount}
                  </td>

                  <td className="px-4 py-3 whitespace-nowrap text-muted">
                    {formatDateTime(
                      row.sentAt,
                    )}
                  </td>

                  <td className="px-4 py-3 whitespace-nowrap text-muted">
                    {formatDateTime(
                      row.deliveredAt,
                    )}
                  </td>

                  <td className="px-4 py-3 whitespace-nowrap text-muted">
                    {formatDateTime(
                      row.readAt,
                    )}
                  </td>

                  <td className="max-w-xs px-4 py-3">
                    {row.lastError ? (
                      <span
                        className="block max-w-xs truncate text-xs text-red-600"
                        title={
                          row.lastError
                        }
                      >
                        {row.lastError}
                      </span>
                    ) : (
                      '—'
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}

/* ================================================================
   STATUS
   ================================================================ */

function StatusBadge({
  status,
}: {
  status: string
}) {
  const normalized =
    status.toUpperCase()

  const className =
    normalized === 'READ'
      ? 'bg-purple-100 text-purple-700'
      : normalized === 'DELIVERED'
        ? 'bg-blue-100 text-blue-700'
        : normalized === 'SENT'
          ? 'bg-green-100 text-green-700'
          : normalized === 'FAILED'
            ? 'bg-red-100 text-red-700'
            : normalized === 'QUEUED' ||
                normalized === 'PENDING' ||
                normalized === 'PROCESSING'
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
