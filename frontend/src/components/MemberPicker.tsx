import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Input } from '@/components/ui'
import { api } from '@/lib/api'
import type { Member, PageResponse } from '@/lib/types'

export function MemberPicker({
  value,
  onChange,
}: {
  value: Member | null
  onChange: (member: Member | null) => void
}) {
  const [q, setQ] = useState('')
  const [debounced, setDebounced] = useState('')
  useEffect(() => {
    const t = setTimeout(() => setDebounced(q), 300)
    return () => clearTimeout(t)
  }, [q])

  const results = useQuery({
    queryKey: ['members', 'picker', debounced],
    queryFn: () =>
      api<PageResponse<Member>>(`/api/v1/members?page=0&size=8${debounced ? `&q=${encodeURIComponent(debounced)}` : ''}`),
    enabled: !value && debounced.length >= 1,
  })

  if (value) {
    return (
      <div className="flex items-center justify-between rounded-md border border-line px-3 py-2 text-sm">
        <span>
          {value.fullName} <span className="text-muted">({value.memberCode})</span>
        </span>
        <button type="button" className="text-xs text-muted hover:text-ink" onClick={() => onChange(null)}>
          Change
        </button>
      </div>
    )
  }

  return (
    <div>
      <Input placeholder="Search members by name, phone, or code" value={q} onChange={(e) => setQ(e.target.value)} />
      {results.data?.content.length ? (
        <ul className="mt-2 divide-y divide-line overflow-hidden rounded-md border border-line">
          {results.data.content.map((m) => (
            <li key={m.id}>
              <button
                type="button"
                className="flex w-full items-center justify-between px-3 py-2 text-left text-sm hover:bg-raised"
                onClick={() => onChange(m)}
              >
                <span>{m.fullName}</span>
                <span className="font-mono text-xs text-muted">{m.memberCode}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
