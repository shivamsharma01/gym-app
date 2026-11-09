import { ArrowLeft } from 'lucide-react'
import { Link, useLocation } from 'react-router'
import { cn } from '@/lib/cn'

/** In-app back link. Prefers the list URL that opened this page so pagination/filters survive. */
export function BackLink({
  to,
  label,
  className,
}: {
  to: string
  label: string
  className?: string
}) {
  const location = useLocation()
  const from = readFrom(location.state)
  const target = from ?? to
  const text = from ? labelFor(from, label) : label

  return (
    <Link
      to={target}
      className={cn(
        'mb-4 inline-flex items-center gap-1.5 text-sm text-muted transition-colors hover:text-ink',
        className,
      )}
    >
      <ArrowLeft className="h-4 w-4" aria-hidden />
      {text}
    </Link>
  )
}

function readFrom(state: unknown): string | null {
  if (state == null || typeof state !== 'object' || !('from' in state)) return null
  const value = (state as { from?: unknown }).from
  return typeof value === 'string' && value.startsWith('/') ? value : null
}

function labelFor(from: string, fallback: string) {
  if (from.startsWith('/app/attendance')) return 'Back to attendance'
  if (from.startsWith('/app/members')) return 'Back to members'
  if (from.startsWith('/app/devices')) return 'Back to devices'
  return fallback
}
