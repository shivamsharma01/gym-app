import { useState } from 'react'

/** Client-side paging for lists the API returns whole; the result spreads straight into {@code <Pager>}. */
export function usePagedRows<T>(rows: T[], pageSize = 20) {
  const [page, setPage] = useState(0)
  const totalPages = Math.max(1, Math.ceil(rows.length / pageSize))
  const current = Math.min(page, totalPages - 1)
  return {
    pageRows: rows.slice(current * pageSize, (current + 1) * pageSize),
    pager: {
      page: current,
      totalPages,
      totalElements: rows.length,
      pageSize,
      onPageChange: setPage,
    },
  }
}
