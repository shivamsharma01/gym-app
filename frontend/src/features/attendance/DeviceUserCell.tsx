import { Link } from 'react-router'
import type { Attendance } from '@/lib/types'

/** Name (and reader id) for an attendance row; links to the member when known. */
export function DeviceUserCell({ row, from }: { row: Attendance; from?: string }) {
  const label = row.memberName ?? (row.deviceUserId ? `User ${row.deviceUserId}` : 'Unknown member')
  const body = (
    <>
      <div className="font-medium">{label}</div>
      {row.deviceUserId && row.memberName ? (
        <div className="mt-0.5 text-xs text-muted">Id {row.deviceUserId}</div>
      ) : null}
    </>
  )
  if (!row.memberId) {
    return <div>{body}</div>
  }
  return (
    <Link
      to={`/app/members/${row.memberId}`}
      state={from ? { from } : undefined}
      className="text-ink hover:text-accent"
    >
      {body}
    </Link>
  )
}
