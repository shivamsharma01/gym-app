import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Badge, Button, Card, EmptyState, FieldError, Input, Label, PageHeader, Select, Skeleton, Textarea } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { CURRENCIES, PLAN_DURATIONS } from '@/lib/catalog'
import { money } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { PageResponse, Plan } from '@/lib/types'

const schema = z.object({
  name: z.string().min(1, 'Required'),
  description: z.string().optional(),
  price: z.string().min(1, 'Required'),
  currency: z.string().length(3),
  durationDays: z.string().min(1, 'Required'),
})

type Form = z.infer<typeof schema>

export function PlansPage() {
  const { has } = useAuth()
  const qc = useQueryClient()
  const [editing, setEditing] = useState<Plan | null>(null)
  const plans = useQuery({
    queryKey: ['plans'],
    queryFn: () => api<PageResponse<Plan>>('/api/v1/plans?size=50'),
  })
  const form = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', description: '', price: '', currency: 'INR', durationDays: '30' },
  })

  const save = useMutation({
    mutationFn: (body: Form) => {
      const payload = {
        name: body.name,
        description: body.description || null,
        price: Number(body.price),
        currency: body.currency.toUpperCase(),
        durationDays: Number(body.durationDays),
      }
      if (editing) {
        return api<Plan>(`/api/v1/plans/${editing.id}`, { method: 'PUT', body: JSON.stringify(payload) })
      }
      return api<Plan>('/api/v1/plans', { method: 'POST', body: JSON.stringify(payload) })
    },
    onSuccess: () => {
      setEditing(null)
      form.reset({ name: '', description: '', price: '', currency: 'INR', durationDays: '30' })
      void qc.invalidateQueries({ queryKey: ['plans'] })
    },
  })

  const archive = useMutation({
    mutationFn: (id: string) => api(`/api/v1/plans/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['plans'] }),
  })

  function startEdit(plan: Plan) {
    setEditing(plan)
    form.reset({
      name: plan.name,
      description: plan.description ?? '',
      price: String(plan.price),
      currency: plan.currency,
      durationDays: String(plan.durationDays),
    })
  }

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(18rem,24rem)]">
      <div>
        <PageHeader title="Plans" description="Membership products. Archiving keeps history on existing memberships." />
        {plans.isLoading ? <Skeleton className="h-40" /> : null}
        {plans.error ? <QueryError error={plans.error} /> : null}
        {plans.data && plans.data.content.length === 0 ? (
          <EmptyState title="No plans" body="Create a plan before starting memberships." />
        ) : null}
        <div className="space-y-3">
          {plans.data?.content.map((plan) => (
            <Card key={plan.id} className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <div className="font-semibold">{plan.name}</div>
                <p className="text-sm text-muted">
                  {money(plan.price, plan.currency)} · {plan.durationDays} days
                </p>
              </div>
              <div className="flex items-center gap-2">
                <Badge tone={statusTone(plan.status)}>{plan.status}</Badge>
                {has('MEMBERSHIP_UPDATE') ? (
                  <Button variant="outline" onClick={() => startEdit(plan)}>
                    Edit
                  </Button>
                ) : null}
                {has('MEMBERSHIP_UPDATE') && plan.status === 'ACTIVE' ? (
                  <Button
                    variant="danger"
                    onClick={() => {
                      if (confirm('Archive this plan? Existing memberships are kept.')) archive.mutate(plan.id)
                    }}
                  >
                    Archive
                  </Button>
                ) : null}
              </div>
            </Card>
          ))}
        </div>
      </div>
      {has('MEMBERSHIP_CREATE') || (editing && has('MEMBERSHIP_UPDATE')) ? (
        <Card>
          <h2 className="mb-4 text-sm font-semibold uppercase tracking-wide text-muted">
            {editing ? 'Edit plan' : 'New plan'}
          </h2>
          <form className="space-y-3" onSubmit={form.handleSubmit((v) => save.mutate(v))}>
            <div>
              <Label>Name</Label>
              <Input {...form.register('name')} />
              <FieldError message={form.formState.errors.name?.message} />
            </div>
            <div>
              <Label>Description</Label>
              <Textarea rows={2} {...form.register('description')} />
            </div>
            <div>
              <Label>Price</Label>
              <Input type="number" step="0.01" {...form.register('price')} />
              <FieldError message={form.formState.errors.price?.message} />
            </div>
            <div>
              <Label>Currency</Label>
              <Select {...form.register('currency')}>
                {CURRENCIES.map((code) => (
                  <option key={code} value={code}>
                    {code}
                  </option>
                ))}
                {editing && !(CURRENCIES as readonly string[]).includes(editing.currency) ? (
                  <option value={editing.currency}>{editing.currency}</option>
                ) : null}
              </Select>
            </div>
            <div>
              <Label>Duration</Label>
              <Select {...form.register('durationDays')}>
                {PLAN_DURATIONS.map((d) => (
                  <option key={d.days} value={String(d.days)}>
                    {d.label}
                  </option>
                ))}
                {editing && !PLAN_DURATIONS.some((d) => d.days === editing.durationDays) ? (
                  <option value={String(editing.durationDays)}>{editing.durationDays} days</option>
                ) : null}
              </Select>
            </div>
            {save.error ? <QueryError error={save.error} /> : null}
            <div className="flex gap-2">
              <Button type="submit" disabled={save.isPending}>
                {editing ? 'Save' : 'Create'}
              </Button>
              {editing ? (
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => {
                    setEditing(null)
                    form.reset({ name: '', description: '', price: '', currency: 'INR', durationDays: '30' })
                  }}
                >
                  Cancel
                </Button>
              ) : null}
            </div>
          </form>
        </Card>
      ) : null}
    </div>
  )
}
