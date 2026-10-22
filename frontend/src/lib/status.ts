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
    if (reason === 'READER_VALIDITY') return 'Allowed (reader validity)'
    return 'Access allowed'
  }
  switch (reason) {
    case 'MEMBER_INACTIVE':
      return 'Denied: account inactive'
    case 'NO_MEMBERSHIP':
      return 'Denied: no plan'
    case 'NO_ACTIVE_MEMBERSHIP':
      return 'Denied: no active plan'
    case 'MEMBERSHIP_FROZEN':
      return 'Denied: plan frozen'
    case 'MEMBERSHIP_EXPIRED':
      return 'Denied: plan expired'
    case 'MEMBERSHIP_CANCELLED':
      return 'Denied: plan cancelled'
    default:
      return reason ? `Denied: ${reason}` : 'Access denied'
  }
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
