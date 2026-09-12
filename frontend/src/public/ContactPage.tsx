import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button, FieldError, Input, Label, Select, Textarea } from '@/components/ui'
import { api } from '@/lib/api'
import { QueryError } from '@/components/QueryError'
import { usePublicPlans, usePublicSite } from '@/public/HomePage'

const schema = z.object({
  name: z.string().min(1, 'Required'),
  email: z.string().email('Valid email required'),
  phone: z.string().optional(),
  planInterest: z.string().optional(),
  message: z.string().min(1, 'Required'),
})

type Form = z.infer<typeof schema>

export function ContactPage() {
  const site = usePublicSite()
  const plans = usePublicPlans()
  const form = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', email: '', phone: '', planInterest: '', message: '' },
  })
  const send = useMutation({
    mutationFn: (body: Form) =>
      api('/api/v1/public/enquiries', {
        method: 'POST',
        body: JSON.stringify({
          name: body.name,
          email: body.email,
          phone: body.phone || null,
          planInterest: body.planInterest || null,
          message: body.message,
        }),
      }),
  })

  return (
    <main className="mx-auto grid max-w-6xl gap-12 px-4 py-16 md:grid-cols-2">
      <div>
        <h1 className="text-4xl font-extrabold">Contact</h1>
        <p className="mt-4 text-white/60">
          {site.data?.address || 'Visit the floor'} · {site.data?.hours || 'Hours on the door'}
        </p>
        <p className="mt-2 text-white/60">
          {site.data?.phone || ''} {site.data?.email || ''}
        </p>
        <p className="mt-6 text-sm text-white/40">This form creates a real enquiry in the gym inbox. It does not send email until a provider is wired.</p>
      </div>
      <form
        className="space-y-4 rounded-3xl border border-white/10 p-6"
        onSubmit={form.handleSubmit((v) => send.mutate(v))}
      >
        <div>
          <Label>Name</Label>
          <Input {...form.register('name')} />
          <FieldError message={form.formState.errors.name?.message} />
        </div>
        <div>
          <Label>Email</Label>
          <Input type="email" {...form.register('email')} />
          <FieldError message={form.formState.errors.email?.message} />
        </div>
        <div>
          <Label>Phone</Label>
          <Input {...form.register('phone')} />
        </div>
        <div>
          <Label>Plan interest</Label>
          <Select {...form.register('planInterest')}>
            <option value="">Not sure yet</option>
            {(plans.data ?? []).map((plan) => (
              <option key={plan.id} value={plan.name}>
                {plan.name}
              </option>
            ))}
          </Select>
        </div>
        <div>
          <Label>Message</Label>
          <Textarea rows={4} {...form.register('message')} />
          <FieldError message={form.formState.errors.message?.message} />
        </div>
        {send.error ? <QueryError error={send.error} /> : null}
        {send.isSuccess ? <p className="text-sm text-ok">Received. Staff will see this in Enquiries.</p> : null}
        <Button type="submit" disabled={send.isPending}>
          Send
        </Button>
      </form>
    </main>
  )
}
