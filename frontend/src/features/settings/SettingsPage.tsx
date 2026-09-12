import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { Button, Input, Label, PageHeader, Skeleton, Textarea } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { BRAND_DEFAULTS, type PublicSite } from '@/lib/brand'
import { Link } from 'react-router'
import { gymPath } from '@/lib/brand'

type SettingsForm = PublicSite

export function SettingsPage() {
  const qc = useQueryClient()
  const settings = useQuery({
    queryKey: ['settings'],
    queryFn: () => api<PublicSite>('/api/v1/settings'),
  })
  const form = useForm<SettingsForm>()
  useEffect(() => {
    if (settings.data) form.reset(settings.data)
  }, [settings.data, form])
  const save = useMutation({
    mutationFn: (body: SettingsForm) =>
      api('/api/v1/settings', {
        method: 'PUT',
        body: JSON.stringify({
          name: body.name,
          displayName: body.displayName,
          tagline: body.tagline,
          about: body.about,
          phone: body.phone,
          email: body.email,
          address: body.address,
          hours: body.hours,
          logoUrl: body.logoUrl,
          heroImageUrl: body.heroImageUrl,
          trainingImageUrl: body.trainingImageUrl,
          facilitiesImageUrl: body.facilitiesImageUrl,
          sectionTrainingTitle: body.sectionTrainingTitle,
          sectionTrainingBody: body.sectionTrainingBody,
          sectionFacilitiesTitle: body.sectionFacilitiesTitle,
          sectionFacilitiesBody: body.sectionFacilitiesBody,
        }),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['settings'] })
      void qc.invalidateQueries({ queryKey: ['public-site'] })
    },
  })
  if (settings.isLoading) return <Skeleton className="h-40" />
  if (settings.error) return <QueryError error={settings.error} />
  const slug = settings.data?.slug
  return (
    <form className="mx-auto max-w-2xl space-y-4" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
      <PageHeader
        title="Gym branding & site"
        description="Public visitors and staff chrome use this profile. Leave image URLs empty to keep bundled defaults."
        actions={
          slug ? (
            <Link to={gymPath(slug)} className="text-sm font-semibold text-accent hover:underline">
              Open public site
            </Link>
          ) : null
        }
      />
      <Label>Legal / internal name</Label>
      <Input {...form.register('name')} />
      <Label>Display name (large brand on the website)</Label>
      <Input {...form.register('displayName')} placeholder="e.g. H13Gym" />
      <Label>Tagline</Label>
      <Input {...form.register('tagline')} />
      <Label>About</Label>
      <Textarea rows={4} {...form.register('about')} />
      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label>Phone</Label>
          <Input {...form.register('phone')} />
        </div>
        <div>
          <Label>Email</Label>
          <Input {...form.register('email')} />
        </div>
      </div>
      <Label>Address</Label>
      <Input {...form.register('address')} />
      <Label>Hours</Label>
      <Input {...form.register('hours')} />

      <h2 className="pt-4 text-sm font-semibold uppercase tracking-wide text-muted">Images (URLs)</h2>
      <p className="text-xs text-muted">
        Defaults live at <code>{BRAND_DEFAULTS.heroImageUrl}</code> etc. Paste an https URL to replace.
      </p>
      <Label>Logo URL</Label>
      <Input {...form.register('logoUrl')} placeholder={BRAND_DEFAULTS.logoUrl} />
      <Label>Hero image URL</Label>
      <Input {...form.register('heroImageUrl')} placeholder={BRAND_DEFAULTS.heroImageUrl} />
      <Label>Training section image URL</Label>
      <Input {...form.register('trainingImageUrl')} />
      <Label>Facilities section image URL</Label>
      <Input {...form.register('facilitiesImageUrl')} />

      <h2 className="pt-4 text-sm font-semibold uppercase tracking-wide text-muted">Homepage sections</h2>
      <Label>Training title</Label>
      <Input {...form.register('sectionTrainingTitle')} />
      <Label>Training body</Label>
      <Textarea rows={3} {...form.register('sectionTrainingBody')} />
      <Label>Facilities title</Label>
      <Input {...form.register('sectionFacilitiesTitle')} />
      <Label>Facilities body</Label>
      <Textarea rows={3} {...form.register('sectionFacilitiesBody')} />

      {save.error ? <QueryError error={save.error} /> : null}
      {save.isSuccess ? <p className="text-sm text-ok">Saved.</p> : null}
      <Button type="submit">Save</Button>
    </form>
  )
}
