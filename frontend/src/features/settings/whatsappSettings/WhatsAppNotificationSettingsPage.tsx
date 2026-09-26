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
} from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { Link } from 'react-router'

type WhatsAppConfiguration = {
    id: string
    publicId?: string

    businessAccountId: string | null
    phoneNumberId: string
    accessToken?: string | null
    apiVersion: string
    active: boolean
}

type WhatsAppConfigurationRequest = {
    businessAccountId: string
    phoneNumberId: string
    accessToken?: string
    apiVersion: string
}

export function WhatsAppNotificationSettingsPage() {
    const qc = useQueryClient()

    const configuration = useQuery({
        queryKey: ['whatsapp-configuration'],
        queryFn: () =>
            api<WhatsAppConfiguration>(
                '/api/v1/whatsapp/configuration',
            ),
        retry: false,
    })

    const [businessAccountId, setBusinessAccountId] =
        useState('')

    const [phoneNumberId, setPhoneNumberId] =
        useState('')

    const [accessToken, setAccessToken] =
        useState('')

    const [apiVersion, setApiVersion] =
        useState('v25.0')

    /*
     * Populate form when configuration loads.
     */
    useEffect(() => {
        if (!configuration.data) {
            return
        }

        setBusinessAccountId(
            configuration.data.businessAccountId ?? '',
        )

        setPhoneNumberId(
            configuration.data.phoneNumberId ?? '',
        )

        /*
         * Never populate the saved access token.
         *
         * The backend should not return the real token
         * after it has been saved.
         */
        setAccessToken('')

        setApiVersion(
            configuration.data.apiVersion ?? 'v25.0',
        )
    }, [configuration.data])

    /*
     * Save WhatsApp configuration.
     */
    const save = useMutation({
        mutationFn: () => {
            const body: WhatsAppConfigurationRequest = {
                businessAccountId,
                phoneNumberId,
                apiVersion,
            }

            /*
             * Only send accessToken when the user entered
             * a new token.
             */
            if (accessToken.trim()) {
                body.accessToken = accessToken.trim()
            }

            return api(
                '/api/v1/whatsapp/configuration',
                {
                    method: 'PUT',
                    body: JSON.stringify(body),
                },
            )
        },

        onSuccess: () => {
            setAccessToken('')

            void qc.invalidateQueries({
                queryKey: ['whatsapp-configuration'],
            })
        },
    })

    /*
     * Disable WhatsApp configuration.
     */
    const disable = useMutation({
        mutationFn: () =>
            api(
                '/api/v1/whatsapp/configuration',
                {
                    method: 'DELETE',
                },
            ),

        onSuccess: () => {
            void qc.invalidateQueries({
                queryKey: ['whatsapp-configuration'],
            })
        },
    })

    const configured =
        !!configuration.data?.active

    return (
        <div className="w-full space-y-6">
            <Link
                to="/app/settings"
                className="inline-flex items-center text-sm font-medium text-muted hover:text-ink hover:underline"
            >
                ← Back to Settings
            </Link>

            <PageHeader
                title="WhatsApp Notifications"
                description="Configure the Meta WhatsApp Cloud API connection used by this gym."
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
                            className="rounded-md bg-raised px-3 py-2 text-sm font-medium text-ink"
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

            {/* ============================================================
          STATUS
         ============================================================ */}

            <Card>
                <div className="flex items-center justify-between gap-4">
                    <div>
                        <div className="text-sm font-semibold">
                            WhatsApp status
                        </div>

                        <div className="mt-1 text-sm text-muted">
                            {configuration.isLoading
                                ? 'Checking WhatsApp configuration...'
                                : configured
                                    ? 'WhatsApp is configured and active.'
                                    : 'WhatsApp is not configured.'}
                        </div>
                    </div>

                    <span
                        className={
                            configured
                                ? 'rounded-full bg-green-100 px-3 py-1 text-xs font-semibold text-green-700'
                                : 'rounded-full bg-gray-100 px-3 py-1 text-xs font-semibold text-gray-700'
                        }
                    >
                        {configured
                            ? 'Configured'
                            : 'Not configured'}
                    </span>
                </div>
            </Card>

            {/* ============================================================
          CONFIGURATION
         ============================================================ */}

            <Card className="space-y-5">
                <div>
                    <h2 className="text-sm font-semibold uppercase text-muted">
                        Meta WhatsApp configuration
                    </h2>

                    <p className="mt-1 text-sm text-muted">
                        These values identify the WhatsApp Cloud API
                        account used by this tenant.
                    </p>
                </div>

                {/* Business account */}
                <div>
                    <Label>
                        WhatsApp Business Account ID
                    </Label>

                    <Input
                        className="mt-1"
                        value={businessAccountId}
                        onChange={(event) =>
                            setBusinessAccountId(
                                event.target.value,
                            )
                        }
                        placeholder="Business Account ID"
                    />
                </div>

                {/* Phone number ID */}
                <div>
                    <Label>
                        WhatsApp Phone Number ID
                    </Label>

                    <Input
                        className="mt-1"
                        value={phoneNumberId}
                        onChange={(event) =>
                            setPhoneNumberId(
                                event.target.value,
                            )
                        }
                        placeholder="Phone Number ID"
                    />

                    <p className="mt-1 text-xs text-muted">
                        This is the Meta phone number ID, not the
                        member's phone number.
                    </p>
                </div>

                {/* Access token */}
                <div>
                    <Label>
                        Access token
                    </Label>

                    <Input
                        className="mt-1"
                        type="password"
                        value={accessToken}
                        onChange={(event) =>
                            setAccessToken(
                                event.target.value,
                            )
                        }
                        placeholder={
                            configured
                                ? 'Enter a new token only if changing it'
                                : 'Meta access token'
                        }
                        autoComplete="new-password"
                    />

                    <p className="mt-1 text-xs text-muted">
                        For security, the saved access token should
                        not be returned by the backend.
                    </p>
                </div>

                {/* API version */}
                <div>
                    <Label>
                        Graph API version
                    </Label>

                    <Input
                        className="mt-1"
                        value={apiVersion}
                        onChange={(event) =>
                            setApiVersion(
                                event.target.value,
                            )
                        }
                        placeholder="v25.0"
                    />
                </div>

                {/* Errors */}
                {save.error ? (
                    <QueryError error={save.error} />
                ) : null}

                {disable.error ? (
                    <QueryError error={disable.error} />
                ) : null}

                {/* Success */}
                {save.isSuccess ? (
                    <p className="text-sm text-green-600">
                        WhatsApp configuration saved.
                    </p>
                ) : null}

                {disable.isSuccess ? (
                    <p className="text-sm text-green-600">
                        WhatsApp configuration disabled.
                    </p>
                ) : null}

                {/* Actions */}
                <div className="flex flex-wrap gap-3">
                    <Button
                        disabled={
                            save.isPending ||
                            !phoneNumberId.trim() ||
                            !apiVersion.trim() ||
                            (!configured &&
                                !accessToken.trim())
                        }
                        onClick={() => save.mutate()}
                    >
                        {save.isPending
                            ? 'Saving...'
                            : 'Save configuration'}
                    </Button>

                    {configured ? (
                        <Button
                            variant="outline"
                            disabled={disable.isPending}
                            onClick={() => {
                                if (
                                    window.confirm(
                                        'Disable WhatsApp notifications for this gym?',
                                    )
                                ) {
                                    disable.mutate()
                                }
                            }}
                        >
                            {disable.isPending
                                ? 'Disabling...'
                                : 'Disable WhatsApp'}
                        </Button>
                    ) : null}
                </div>
            </Card>

            {/* ============================================================
          TEMPLATE LINK
         ============================================================ */}

            <Card>
                <div className="flex items-center justify-between gap-4">
                    <div>
                        <h2 className="font-semibold">
                            WhatsApp templates
                        </h2>

                        <p className="mt-1 text-sm text-muted">
                            Map your application events to the
                            templates created in Meta.
                        </p>
                    </div>

                    <Link
                        to="/app/notifications/templates"
                        className="text-sm font-semibold text-accent hover:underline"
                    >
                        Manage mappings →
                    </Link>
                </div>
            </Card>
        </div>
    )
}
