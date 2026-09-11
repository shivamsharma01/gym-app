import { clsx, type ClassValue } from 'clsx'
import { format, isValid, parseISO } from 'date-fns'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatDate(value?: string | null) {
  if (!value) return '—'
  const d = parseISO(value.length === 10 ? `${value}T00:00:00` : value)
  if (!isValid(d)) return value.slice(0, 10)
  return format(d, 'yyyy-MM-dd')
}

export function formatDateTime(value?: string | null) {
  if (!value) return '—'
  const d = parseISO(value)
  if (!isValid(d)) return value
  return format(d, 'PPp')
}

export function money(amount: number | string, currency = 'INR') {
  const n = typeof amount === 'string' ? Number(amount) : amount
  if (Number.isNaN(n)) return String(amount)
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(n)
  } catch {
    return `${currency} ${n.toFixed(2)}`
  }
}
