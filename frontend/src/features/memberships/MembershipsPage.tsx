import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { MemberPicker } from '@/components/MemberPicker'
import { PageHeader, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { MembershipPanel } from '@/features/memberships/MembershipPanel'
import { api } from '@/lib/api'
import type { Member, Membership } from '@/lib/types'

export function MembershipsPage() {
  const [member, setMember] = useState<Member | null>(null)
  const memberships = useQuery({
    queryKey: ['memberships', member?.id],
    queryFn: () => api<Membership[]>(`/api/v1/members/${member!.id}/memberships`),
    enabled: Boolean(member),
  })

  return (
    <div>
      <PageHeader
        title="Memberships"
        description="There is no global membership list. Pick a member to start, freeze, renew, or cancel."
      />
      <div className="mb-6 max-w-lg">
        <MemberPicker value={member} onChange={setMember} />
      </div>
      {member ? (
        <p className="mb-4 text-sm text-muted">
          Viewing{' '}
          <Link className="font-semibold text-ink hover:underline" to={`/app/members/${member.id}`}>
            {member.fullName}
          </Link>
        </p>
      ) : null}
      {memberships.isLoading ? <Skeleton className="h-32" /> : null}
      {memberships.error ? <QueryError error={memberships.error} /> : null}
      {member && memberships.data ? <MembershipPanel memberId={member.id} rows={memberships.data} /> : null}
    </div>
  )
}
