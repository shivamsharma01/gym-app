import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { Button, Input, Label, PageHeader, Skeleton, Textarea } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import type { PublicSite } from '@/public/HomePage'

export function SettingsPage() {
  const qc = useQueryClient()
  const settings = useQuery({
    queryKey: ['settings'],
    queryFn: () => api<PublicSite>('/api/v1/settings'),
  })
  const form = useForm<PublicSite>()
  useEffect(() => {
    if (settings.data) form.reset(settings.data)
  }, [settings.data, form])
  const save = useMutation({
    mutationFn: (body: PublicSite) =>
      api('/api/v1/settings', {
        method: 'PUT',
        body: JSON.stringify({
          name: body.name,
          tagline: body.tagline,
          about: body.about,
          phone: body.phone,
          email: body.email,
          address: body.address,
          hours: body.hours,
        }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['settings'] }),
  })
  if (settings.isLoading) return <Skeleton className="h-40" />
  if (settings.error) return <QueryError error={settings.error} />
  return (
    <form className="max-w-xl space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
      <PageHeader title="Settings" description="This profile is what the public website reads." />
      <Label>Gym name</Label>
      <Input {...form.register('name')} />
      <Label>Tagline</Label>
      <Input {...form.register('tagline')} />
      <Label>About</Label>
      <Textarea rows={4} {...form.register('about')} />
      <Label>Phone</Label>
      <Input {...form.register('phone')} />
      <Label>Email</Label>
      <Input {...form.register('email')} />
      <Label>Address</Label>
      <Input {...form.register('address')} />
      <Label>Hours</Label>
      <Input {...form.register('hours')} />
      {save.error ? <QueryError error={save.error} /> : null}
      <Button type="submit">Save</Button>
    </form>
  )
}
