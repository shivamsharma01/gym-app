export type Tone = 'ok' | 'warn' | 'danger' | 'muted' | 'accent'

export function membershipStatusLabel(value?: string | null): string {
  switch (value) {
    case 'ACTIVE':
      return 'Active'
    case 'PENDING':
      return 'Pending'
    case 'FROZEN':
      return 'Frozen'
    case 'EXPIRED':
      return 'Expired'
    case 'CANCELLED':
      return 'Cancelled'
    case 'NO_PLAN':
      return 'No plan'
    default:
      return value || '—'
  }
}

export function accountStatusLabel(value?: string | null): string {
  if (value === 'ACTIVE') return 'Active'
  if (value === 'INACTIVE') return 'Inactive'
  return value || '—'
}

/** Staff-facing label for `/access` reason codes. */
export function accessReasonLabel(allowed: boolean, reason?: string | null): string {
  if (allowed) {
    if (reason === 'READER_VALIDITY') return 'Allowed'
    if (reason === 'READER_VALIDITY_NO_ACTIVE_PLAN') return 'Allowed. No plan'
    return 'Allowed'
  }
  switch (reason) {
    case 'READER_VALIDITY_EXPIRED':
      return 'Denied. Validity ended'
    case 'READER_VALIDITY_NOT_STARTED':
      return 'Denied. Validity has not started'
    case 'MEMBER_INACTIVE':
      return 'Denied. Account is off'
    case 'NO_MEMBERSHIP':
      return 'Denied. No plan'
    case 'NO_ACTIVE_MEMBERSHIP':
      return 'Denied. No active plan'
    case 'MEMBERSHIP_FROZEN':
      return 'Denied. Plan is frozen'
    case 'MEMBERSHIP_EXPIRED':
      return 'Denied. Plan expired'
    case 'MEMBERSHIP_CANCELLED':
      return 'Denied. Plan cancelled'
    default:
      return reason ? `Denied: ${reason}` : 'Access denied'
  }
}

/** A reader stays online only while the gym PC that talks to it is online. */
export function readerConnection(reported: string, gatewayStatus: string | null | undefined, assigned: boolean) {
  if (!assigned || !gatewayStatus || gatewayStatus === 'ONLINE') return reported
  if (gatewayStatus === 'OFFLINE' || reported === 'ONLINE') return 'OFFLINE'
  return reported
}

export function statusTone(value?: string | null): Tone {
  const v = (value ?? '').toUpperCase()
  if (v === 'NOT_SYNCED' || v === 'INACTIVE') {
    return v === 'INACTIVE' ? 'danger' : 'warn'
  }
  if (['ACTIVE', 'ONLINE', 'SYNCED', 'GRANTED', 'SUCCEEDED', 'ENROLLED', 'PAID', 'COMPLETED'].some((x) => v.includes(x))) {
    return 'ok'
  }
  if (['PENDING', 'RETRYING', 'DISPATCHED', 'UNKNOWN', 'UNPAID', 'FROZEN', 'EXPIRED'].some((x) => v.includes(x))) {
    return 'warn'
  }
  if (['FAILED', 'DENIED', 'DEAD', 'CANCELLED', 'OFFLINE', 'INACTIVE'].some((x) => v.includes(x))) {
    return 'danger'
  }
  return 'muted'
}
