import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { MemberPicker } from '@/components/MemberPicker'
import { Button, Card, Input, Label, PageHeader, Select, Textarea } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/cn'
import type { Member, PageResponse } from '@/lib/types'

type Template = { id: string; templateKey: string; channel: string; subject: string | null; body: string }
type Outbound = {
  id: string
  channel: string
  templateKey: string | null
  recipient: string
  status: string
  sentAt: string | null
  createdAt: string
}
type Announcement = { id: string; title: string; body: string; published: boolean; createdAt: string }

export function NotificationsPage() {
  const qc = useQueryClient()
  const outbound = useQuery({
    queryKey: ['notifications'],
    queryFn: () => api<PageResponse<Outbound>>('/api/v1/notifications?size=30'),
  })
  const templates = useQuery({
    queryKey: ['notification-templates'],
    queryFn: () => api<Template[]>('/api/v1/notification-templates'),
  })
  const [member, setMember] = useState<Member | null>(null)
  const [templateKey, setTemplateKey] = useState('EXPIRY_REMINDER')
  const [channel, setChannel] = useState('EMAIL')
  const send = useMutation({
    mutationFn: () =>
      api('/api/v1/notifications', {
        method: 'POST',
        body: JSON.stringify({ memberId: member?.id, templateKey, channel }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notifications'] }),
  })
  const reminders = useMutation({
    mutationFn: () => api('/api/v1/notifications/expiry-reminders', { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notifications'] }),
  })

  return (
    <div className="space-y-8">
      <PageHeader
        title="Notifications"
        description="Delivery is a mock adapter: rows are marked SENT without an email/SMS vendor."
      />
      <p className="text-sm">
        <a className="underline" href="/app/notifications/templates">
          Templates
        </a>
        {' · '}
        <a className="underline" href="/app/announcements">
          Announcements
        </a>
      </p>
      <Card className="space-y-3">
        <h2 className="text-sm font-semibold uppercase text-muted">Send to a member</h2>
        <MemberPicker value={member} onChange={setMember} />
        <Select value={templateKey} onChange={(e) => setTemplateKey(e.target.value)}>
          {(templates.data && templates.data.length > 0
            ? templates.data
            : [{ templateKey: 'EXPIRY_REMINDER', channel: 'EMAIL' }]
          ).map((t) => (
            <option key={t.templateKey + t.channel} value={t.templateKey}>
              {t.templateKey} ({t.channel})
            </option>
          ))}
        </Select>
        <Select value={channel} onChange={(e) => setChannel(e.target.value)}>
          <option value="EMAIL">EMAIL</option>
          <option value="SMS">SMS</option>
          <option value="IN_APP">IN_APP</option>
        </Select>
        {send.error ? <QueryError error={send.error} /> : null}
        <Button disabled={!member || send.isPending} onClick={() => send.mutate()}>
          Queue send
        </Button>
        <Button variant="outline" onClick={() => reminders.mutate()}>
          Run expiry reminders
        </Button>
      </Card>
      <ul className="divide-y divide-line rounded-xl border border-line">
        {outbound.data?.content.map((row) => (
          <li key={row.id} className="flex justify-between px-4 py-3 text-sm">
            <span>
              {row.channel} → {row.recipient}
            </span>
            <span className="text-muted">
              {row.status} · {formatDateTime(row.sentAt ?? row.createdAt)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function NotificationTemplatesPage() {
  const qc = useQueryClient()
  const templates = useQuery({
    queryKey: ['notification-templates'],
    queryFn: () => api<Template[]>('/api/v1/notification-templates'),
  })
  const [templateKey, setTemplateKey] = useState('EXPIRY_REMINDER')
  const [channel, setChannel] = useState('EMAIL')
  const [subject, setSubject] = useState('{{memberName}}, your membership')
  const [body, setBody] = useState('Hi {{memberName}}. Expires {{expiryDate}} ({{daysRemaining}} days). {{gymName}}')
  const save = useMutation({
    mutationFn: () =>
      api('/api/v1/notification-templates', {
        method: 'PUT',
        body: JSON.stringify({ templateKey, channel, subject, body }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notification-templates'] }),
  })
  return (
    <div className="max-w-xl space-y-4">
      <PageHeader title="Templates" description="Variables: {{memberName}} {{gymName}} {{expiryDate}} {{daysRemaining}}" />
      {templates.data?.map((t) => (
        <p key={t.id} className="text-sm text-muted">
          {t.templateKey} / {t.channel}
        </p>
      ))}
      <Label>Key</Label>
      <Input value={templateKey} onChange={(e) => setTemplateKey(e.target.value)} />
      <Label>Channel</Label>
      <Select value={channel} onChange={(e) => setChannel(e.target.value)}>
        <option>EMAIL</option>
        <option>SMS</option>
        <option>IN_APP</option>
      </Select>
      <Label>Subject</Label>
      <Input value={subject} onChange={(e) => setSubject(e.target.value)} />
      <Label>Body</Label>
      <Textarea rows={4} value={body} onChange={(e) => setBody(e.target.value)} />
      {save.error ? <QueryError error={save.error} /> : null}
      <Button onClick={() => save.mutate()}>Save template</Button>
    </div>
  )
}

export function AnnouncementsPage() {
  const qc = useQueryClient()
  const list = useQuery({
    queryKey: ['announcements'],
    queryFn: () => api<Announcement[]>('/api/v1/announcements'),
  })
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const create = useMutation({
    mutationFn: () =>
      api('/api/v1/announcements', {
        method: 'POST',
        body: JSON.stringify({ title, body, published: true }),
      }),
    onSuccess: () => {
      setTitle('')
      setBody('')
      void qc.invalidateQueries({ queryKey: ['announcements'] })
    },
  })
  return (
    <div className="max-w-xl space-y-4">
      <PageHeader title="Announcements" />
      <Input placeholder="Title" value={title} onChange={(e) => setTitle(e.target.value)} />
      <Textarea rows={3} value={body} onChange={(e) => setBody(e.target.value)} />
      <Button disabled={!title || !body} onClick={() => create.mutate()}>
        Publish
      </Button>
      <ul className="space-y-3">
        {list.data?.map((a) => (
          <li key={a.id} className="rounded-xl border border-line p-4">
            <div className="font-semibold">{a.title}</div>
            <p className="text-sm text-muted">{a.body}</p>
          </li>
        ))}
      </ul>
    </div>
  )
}
