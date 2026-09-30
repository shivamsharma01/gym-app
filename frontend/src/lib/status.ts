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

export function statusTone(value?: string | null): Tone {
  const v = (value ?? '').toUpperCase()
  if (v === 'NOT_SYNCED' || v === 'INACTIVE') {
    return v === 'INACTIVE' ? 'danger' : 'warn'
  }
  if (['ACTIVE', 'ONLINE', 'SYNCED', 'GRANTED', 'SUCCEEDED', 'ENROLLED', 'PAID', 'COMPLETED'].some((x) => v.includes(x))) {
    return 'ok'
  }
  if (['PENDING', 'RETRYING', 'DISPATCHED', 'UNKNOWN', 'GUIDED', 'UNPAID', 'FROZEN', 'EXPIRED'].some((x) => v.includes(x))) {
    return 'warn'
  }
  if (['FAILED', 'DENIED', 'DEAD', 'CANCELLED', 'OFFLINE', 'INACTIVE'].some((x) => v.includes(x))) {
    return 'danger'
  }
  return 'muted'
}
