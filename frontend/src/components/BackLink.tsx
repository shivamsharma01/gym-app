import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router'
import { cn } from '@/lib/cn'

/** In-app back link to a parent list or detail route. */
export function BackLink({
  to,
  label,
  className,
}: {
  to: string
  label: string
  className?: string
}) {
  return (
    <Link
      to={to}
      className={cn(
        'mb-4 inline-flex items-center gap-1.5 text-sm text-muted transition-colors hover:text-ink',
        className,
      )}
    >
      <ArrowLeft className="h-4 w-4" aria-hidden />
      {label}
    </Link>
  )
}
