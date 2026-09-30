import type { ReactNode } from 'react'
import { ChevronFirst, ChevronLast, ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui'
import { cn } from '@/lib/cn'
import type { PageResponse } from '@/lib/types'

/**
 * Zero-based page navigation with first / previous / next / last controls. With
 * {@code totalElements} and {@code pageSize} it also shows the "1–20 of 57" range.
 */
export function Pager({
  page,
  totalPages,
  onPageChange,
  totalElements,
  pageSize,
  extra,
  className,
}: {
  page: number
  totalPages: number
  onPageChange: (page: number) => void
  totalElements?: number
  pageSize?: number
  /** Rendered before the buttons, e.g. a rows-per-page select. */
  extra?: ReactNode
  className?: string
}) {
  const pages = Math.max(totalPages, 1)
  const current = Math.min(Math.max(page, 0), pages - 1)
  const atStart = current === 0
  const atEnd = current >= pages - 1
  const showRange = totalElements !== undefined && pageSize !== undefined
  const start = showRange && totalElements > 0 ? current * pageSize + 1 : 0
  const end = showRange ? Math.min((current + 1) * pageSize, totalElements) : 0

  return (
    <div className={cn('mt-3 flex flex-wrap items-center justify-between gap-3', className)}>
      {showRange ? (
        <p className="text-xs tabular-nums text-muted">
          {start}–{end} of {totalElements}
        </p>
      ) : (
        <span />
      )}
      <div className="flex flex-wrap items-center gap-2">
        {extra}
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
    </div>
  )
}

/** A {@link Pager} wired to a Spring page response. */
export function PageNav<T>({
  data,
  onPageChange,
  extra,
  className,
}: {
  data: PageResponse<T>
  onPageChange: (page: number) => void
  extra?: ReactNode
  className?: string
}) {
  return (
    <Pager
      page={data.page}
      totalPages={data.totalPages}
      totalElements={data.totalElements}
      pageSize={data.size}
      onPageChange={onPageChange}
      extra={extra}
      className={className}
    />
  )
}
