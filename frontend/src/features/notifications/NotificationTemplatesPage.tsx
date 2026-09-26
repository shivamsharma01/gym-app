import { useEffect, useState } from 'react'
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
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

type TemplateVariable = {
  id: string
  variableName: string
  variableOrder: number
}

type Template = {
  id: string
  templateKey: string
  channel: string
  subject: string | null
  body: string | null
  whatsappTemplateName: string | null
  whatsappLanguage: string | null
  active: boolean
  whatsappVariables?: TemplateVariable[]
}

const TEMPLATE_KEYS = [
  'MEMBERSHIP_CREATED',
  'MEMBERSHIP_RENEWED',
  'MEMBERSHIP_FROZEN',
  'MEMBERSHIP_UNFROZEN',
  'MEMBERSHIP_CANCELLED',
  'MEMBERSHIP_DATES_UPDATED',
  'EXPIRY_REMINDER_3_DAYS',
  'MEMBERSHIP_EXPIRED',
]

const VARIABLES = [
  'memberName',
  'memberCode',
  'gymName',
  'membershipPlan',
  'startDate',
  'expiryDate',
  'daysRemaining',
  'amount',
  'currency',
  'amountPaid',
  'membershipStatus',
]

export function NotificationTemplatesPage() {
  const qc = useQueryClient()

  const templates = useQuery({
    queryKey: ['notification-templates'],
    queryFn: () =>
      api<Template[]>(
        '/api/v1/notification-templates',
      ),
  })

  const [templateKey, setTemplateKey] =
    useState('MEMBERSHIP_CREATED')

  const [whatsappTemplateName, setWhatsappTemplateName] =
    useState('')

  const [whatsappLanguage, setWhatsappLanguage] =
    useState('en_US')

  const [selectedVariables, setSelectedVariables] =
    useState<string[]>([])

  const selectedTemplate =
    templates.data?.find(
      (template) =>
        template.templateKey ===
        templateKey &&
        template.channel === 'WHATSAPP',
    )

  /*
   * Populate form when event changes.
   */
  useEffect(() => {
    if (!selectedTemplate) {
      setWhatsappTemplateName('')
      setWhatsappLanguage('en_US')
      setSelectedVariables([])
      return
    }

    setWhatsappTemplateName(
      selectedTemplate.whatsappTemplateName ??
      '',
    )

    setWhatsappLanguage(
      selectedTemplate.whatsappLanguage ??
      'en_US',
    )

    setSelectedVariables(
      [...(selectedTemplate.whatsappVariables ?? [])]
        .sort(
          (a, b) =>
            a.variableOrder -
            b.variableOrder,
        )
        .map(
          (variable) =>
            variable.variableName,
        ),
    )
  }, [selectedTemplate])

  /*
   * Save application mapping.
   *
   * This does NOT create a Meta template.
   */
  const save = useMutation({
    mutationFn: () =>
      api('/api/v1/notification-templates', {
        method: 'PUT',
        body: JSON.stringify({
          templateKey,
          channel: 'WHATSAPP',
          whatsappTemplateName,
          whatsappLanguage,
          variables:
            selectedVariables.map(
              (variable, index) => ({
                variableName: variable,
                variableOrder: index + 1,
              }),
            ),
        }),
      }),

    onSuccess: () => {
      void qc.invalidateQueries({
        queryKey: ['notification-templates'],
      })
    },
  })

  /*
   * Activate/deactivate template mapping.
   */
  const toggleActive = useMutation({
    mutationFn: () =>
      api(
        `/api/v1/notification-templates/${selectedTemplate?.id}/active`,
        {
          method: 'PUT',
          body: JSON.stringify({
            active:
              !selectedTemplate?.active,
          }),
        },
      ),

    onSuccess: () => {
      void qc.invalidateQueries({
        queryKey: ['notification-templates'],
      })
    },
  })

  function toggleVariable(variable: string) {
    setSelectedVariables((current) => {
      if (current.includes(variable)) {
        return current.filter(
          (item) => item !== variable,
        )
      }

      return [...current, variable]
    })
  }

  const isConfigured =
    !!selectedTemplate &&
    !!selectedTemplate.whatsappTemplateName

  return (
    <div className="w-full space-y-6">
      <PageHeader
        title="WhatsApp Notification Templates"
        description="Map your application events to WhatsApp templates created and approved in Meta."
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
              className="rounded-md bg-raised px-3 py-2 text-sm font-medium text-ink"
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



      <Card className="space-y-6">
        {/* Event */}
        <div>
          <Label>
            Application notification event
          </Label>

          <Select
            className="mt-1"
            value={templateKey}
            onChange={(event) =>
              setTemplateKey(
                event.target.value,
              )
            }
          >
            {TEMPLATE_KEYS.map((key) => (
              <option
                key={key}
                value={key}
              >
                {key}
              </option>
            ))}
          </Select>
        </div>

        {/* Status */}
        <div className="flex items-center justify-between rounded-lg border border-line bg-raised p-4">
          <div>
            <div className="font-medium">
              Configuration status
            </div>

            <div className="mt-1 text-sm text-muted">
              {isConfigured
                ? 'Meta template is mapped to this application event.'
                : 'No WhatsApp template is mapped yet.'}
            </div>
          </div>

          <span
            className={
              isConfigured
                ? 'rounded-full bg-green-100 px-3 py-1 text-xs font-semibold text-green-700'
                : 'rounded-full bg-yellow-100 px-3 py-1 text-xs font-semibold text-yellow-700'
            }
          >
            {isConfigured
              ? 'Configured'
              : 'Not configured'}
          </span>
        </div>

        {/* Meta template name */}
        <div>
          <Label>
            Meta WhatsApp template name
          </Label>

          <Input
            className="mt-1"
            value={whatsappTemplateName}
            onChange={(event) =>
              setWhatsappTemplateName(
                event.target.value,
              )
            }
            placeholder="e.g. membership_created"
          />

          <p className="mt-1 text-xs text-muted">
            Enter the exact template name created
            in Meta WhatsApp Manager.
          </p>
        </div>

        {/* Language */}
        <div>
          <Label>
            WhatsApp template language
          </Label>

          <Input
            className="mt-1"
            value={whatsappLanguage}
            readOnly
            placeholder="en_US"
          />

          <p className="mt-1 text-xs text-muted">
            This must match the language code of
            the Meta template.
          </p>
        </div>

        {/* Variables */}
        <div>
          <Label>
            Template variables
          </Label>

          <p className="mt-1 text-sm text-muted">
            Select the application values that
            correspond to the Meta template
            parameters. The order is important.
          </p>

          <div className="mt-4 grid gap-2 sm:grid-cols-2">
            {VARIABLES.map((variable) => {
              const selected =
                selectedVariables.includes(
                  variable,
                )

              const order =
                selectedVariables.indexOf(
                  variable,
                ) + 1

              return (
                <button
                  type="button"
                  key={variable}
                  onClick={() =>
                    toggleVariable(variable)
                  }
                  className={`flex items-center justify-between rounded-lg border p-3 text-left ${selected
                    ? 'border-accent bg-raised'
                    : 'border-line'
                    }`}
                >
                  <code className="text-sm">
                    {`{{${variable}}}`}
                  </code>

                  {selected ? (
                    <span className="text-xs font-semibold text-accent">
                      #{order}
                    </span>
                  ) : null}
                </button>
              )
            })}
          </div>
        </div>

        {/* Selected order */}
        {selectedVariables.length > 0 ? (
          <div className="rounded-lg border border-line bg-raised p-4">
            <div className="text-xs font-semibold uppercase text-muted">
              Parameter order
            </div>

            <div className="mt-3 space-y-2">
              {selectedVariables.map(
                (variable, index) => (
                  <div
                    key={variable}
                    className="flex items-center gap-3 text-sm"
                  >
                    <span className="flex h-6 w-6 items-center justify-center rounded-full bg-panel text-xs font-semibold">
                      {index + 1}
                    </span>

                    <code>
                      {`{{${variable}}}`}
                    </code>
                  </div>
                ),
              )}
            </div>
          </div>
        ) : null}

        {save.error ? (
          <QueryError error={save.error} />
        ) : null}

        <div className="flex flex-wrap gap-3">
          <Button
            disabled={
              save.isPending ||
              !whatsappTemplateName.trim() ||
              selectedVariables.length === 0
            }
            onClick={() => save.mutate()}
          >
            {save.isPending
              ? 'Saving...'
              : 'Save template mapping'}
          </Button>

          {selectedTemplate ? (
            <Button
              variant="outline"
              disabled={toggleActive.isPending}
              onClick={() =>
                toggleActive.mutate()
              }
            >
              {selectedTemplate.active
                ? 'Disable'
                : 'Enable'}
            </Button>
          ) : null}
        </div>
      </Card>

      {/* Current mappings */}
      <Card className="space-y-4">
        <div>
          <h2 className="text-sm font-semibold uppercase text-muted">
            Configured WhatsApp templates
          </h2>

          <p className="mt-1 text-sm text-muted">
            These are application mappings to
            templates managed in Meta.
          </p>
        </div>

        {templates.isLoading ? (
          <p className="text-sm text-muted">
            Loading templates...
          </p>
        ) : templates.error ? (
          <QueryError error={templates.error} />
        ) : !templates.data?.length ? (
          <p className="text-sm text-muted">
            No notification templates configured.
          </p>
        ) : (
          <div className="overflow-x-auto rounded-xl border border-line">
            <table className="w-full text-sm">
              <thead className="border-b border-line bg-raised">
                <tr>
                  <th className="px-4 py-3 text-left">
                    Event
                  </th>

                  <th className="px-4 py-3 text-left">
                    Meta template
                  </th>

                  <th className="px-4 py-3 text-left">
                    Language
                  </th>

                  <th className="px-4 py-3 text-left">
                    Variables
                  </th>

                  <th className="px-4 py-3 text-left">
                    Status
                  </th>
                </tr>
              </thead>

              <tbody className="divide-y divide-line">
                {templates.data
                  .filter(
                    (template) =>
                      template.channel ===
                      'WHATSAPP',
                  )
                  .map((template) => (
                    <tr
                      key={template.id}
                    >
                      <td className="px-4 py-3 font-medium">
                        {template.templateKey}
                      </td>

                      <td className="px-4 py-3">
                        {template.whatsappTemplateName ??
                          '—'}
                      </td>

                      <td className="px-4 py-3">
                        {template.whatsappLanguage ??
                          '—'}
                      </td>

                      <td className="px-4 py-3">
                        {template.whatsappVariables
                          ?.length ?? 0}
                      </td>

                      <td className="px-4 py-3">
                        <span
                          className={
                            template.active
                              ? 'rounded-full bg-green-100 px-2 py-1 text-xs font-semibold text-green-700'
                              : 'rounded-full bg-gray-100 px-2 py-1 text-xs font-semibold text-gray-700'
                          }
                        >
                          {template.active
                            ? 'Active'
                            : 'Inactive'}
                        </span>
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
