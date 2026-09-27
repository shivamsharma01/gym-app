export type Tone = 'ok' | 'warn' | 'danger' | 'muted' | 'accent'

export function statusTone(value?: string | null): Tone {
  const v = (value ?? '').toUpperCase()
  if (v === 'NOT_SYNCED' || v === 'INACTIVE') {
    return v === 'INACTIVE' ? 'danger' : 'warn'
  }
  if (['ACTIVE', 'ONLINE', 'SYNCED', 'GRANTED', 'SUCCEEDED', 'ENROLLED', 'PAID', 'COMPLETED'].some((x) => v.includes(x))) {
    return 'ok'
  }
  if (['PENDING', 'RETRYING', 'DISPATCHED', 'UNKNOWN', 'GUIDED', 'UNPAID', 'FROZEN'].some((x) => v.includes(x))) {
    return 'warn'
  }
  if (['FAILED', 'DENIED', 'DEAD', 'CANCELLED', 'OFFLINE', 'INACTIVE'].some((x) => v.includes(x))) {
    return 'danger'
  }
  return 'muted'
}
