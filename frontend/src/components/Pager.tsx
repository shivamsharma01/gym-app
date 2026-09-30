import { ChevronFirst, ChevronLast, ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui'
import { cn } from '@/lib/cn'

/** Zero-based page navigation with first / previous / next / last controls. */
export function Pager({
  page,
  totalPages,
  onPageChange,
  className,
}: {
  page: number
  totalPages: number
  onPageChange: (page: number) => void
  className?: string
}) {
  const pages = Math.max(totalPages, 1)
  const current = Math.min(Math.max(page, 0), pages - 1)
  const atStart = current === 0
  const atEnd = current >= pages - 1

  return (
    <div className={cn('flex flex-wrap items-center gap-2', className)}>
      <Button variant="outline" size="sm" disabled={atStart} onClick={() => onPageChange(0)} aria-label="First page">
        <ChevronFirst className="h-4 w-4" />
      </Button>
      <Button variant="outline" size="sm" disabled={atStart} onClick={() => onPageChange(current - 1)}>
        <ChevronLeft className="h-4 w-4" />
        Previous
      </Button>
      <span className="text-xs tabular-nums text-muted">
        {current + 1} / {pages}
      </span>
      <Button variant="outline" size="sm" disabled={atEnd} onClick={() => onPageChange(current + 1)}>
        Next
        <ChevronRight className="h-4 w-4" />
      </Button>
      <Button variant="outline" size="sm" disabled={atEnd} onClick={() => onPageChange(pages - 1)} aria-label="Last page">
        <ChevronLast className="h-4 w-4" />
      </Button>
    </div>
  )
}
