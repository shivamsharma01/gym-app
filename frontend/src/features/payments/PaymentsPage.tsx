import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { MemberPicker } from '@/components/MemberPicker'
import { Badge, Button, Card, EmptyState, FieldError, Input, Label, PageHeader, Select, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { CURRENCIES } from '@/lib/catalog'
import { formatDate, money } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Member, Membership, PageResponse, Payment } from '@/lib/types'

const schema = z.object({
  amount: z.string().min(1, 'Required'),
  currency: z.string().length(3),
  method: z.string(),
  reference: z.string().optional(),
  paidOn: z.string().optional(),
  notes: z.string().optional(),
  membershipId: z.string().optional(),
})

type Form = z.infer<typeof schema>

export function PaymentsPage() {
  const { has } = useAuth()
  const qc = useQueryClient()
  const [page, setPage] = useState(0)
  const [member, setMember] = useState<Member | null>(null)
  const payments = useQuery({
    queryKey: ['payments', page],
    queryFn: () => api<PageResponse<Payment>>(`/api/v1/payments?page=${page}&size=20`),
  })
  const form = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { amount: '', currency: 'INR', method: 'CASH', reference: '', paidOn: '', notes: '', membershipId: '' },
  })
  const memberships = useQuery({
    queryKey: ['memberships', member?.id],
    queryFn: () => api<Membership[]>(`/api/v1/members/${member!.id}/memberships`),
    enabled: Boolean(member),
  })
  const membershipId = form.watch('membershipId')
  const selectedMembership = memberships.data?.find((m) => m.id === membershipId)
  const currencyOptions = selectedMembership
    ? Array.from(new Set([selectedMembership.currency, ...CURRENCIES]))
    : [...CURRENCIES]

  useEffect(() => {
    if (!member) {
      form.setValue('membershipId', '')
      form.setValue('amount', '')
      form.setValue('currency', 'INR')
      return
    }
    if (!memberships.data) return
    const unpaid = memberships.data.find((m) => m.status !== 'CANCELLED' && m.paymentStatus !== 'PAID')
    form.setValue('membershipId', unpaid?.id ?? '')
  }, [member, memberships.data, form])

  useEffect(() => {
    if (!selectedMembership) return
    const remaining = Number(selectedMembership.price) - Number(selectedMembership.amountPaid)
    if (remaining > 0) form.setValue('amount', remaining.toFixed(2))
    form.setValue('currency', selectedMembership.currency)
  }, [selectedMembership, form])

  const record = useMutation({
    mutationFn: (body: Form) => {
      if (!member) throw new Error('Pick a member')
      return api<Payment>('/api/v1/payments', {
        method: 'POST',
        body: JSON.stringify({
          memberId: member.id,
          membershipId: body.membershipId || null,
          amount: Number(body.amount),
          currency: body.currency.toUpperCase(),
          method: body.method,
          reference: body.reference || null,
          paidOn: body.paidOn || null,
          notes: body.notes || null,
        }),
      })
    },
    onSuccess: () => {
      setMember(null)
      form.reset({ amount: '', currency: 'INR', method: 'CASH', reference: '', paidOn: '', notes: '', membershipId: '' })
      void qc.invalidateQueries({ queryKey: ['payments'] })
    },
  })
  const refund = useMutation({
    mutationFn: (id: string) => api<Payment>(`/api/v1/payments/${id}/refund`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['payments'] }),
  })

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(20rem,26rem)]">
      <div>
        <PageHeader title="Payments" description="Recorded against members. Refunds require PAYMENT_CREATE." />
        {payments.isLoading ? <Skeleton className="h-40" /> : null}
        {payments.error ? <QueryError error={payments.error} /> : null}
        {payments.data && payments.data.content.length === 0 ? (
          <EmptyState title="No payments" body="Record a cash, UPI, or card payment for a member." />
        ) : null}
        {payments.data && payments.data.content.length > 0 ? (
          <div className="overflow-x-auto rounded-xl border border-line">
            <table className="w-full min-w-[640px] text-left text-sm">
              <thead className="bg-raised text-xs uppercase tracking-wide text-muted">
                <tr>
                  <th className="px-4 py-3">Paid on</th>
                  <th className="px-4 py-3">Amount</th>
                  <th className="px-4 py-3">Method</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody>
                {payments.data.content.map((p) => (
                  <tr key={p.id} className="border-t border-line">
                    <td className="px-4 py-3">{formatDate(p.paidOn)}</td>
                    <td className="px-4 py-3">{money(p.amount, p.currency)}</td>
                    <td className="px-4 py-3 text-muted">{p.method}</td>
                    <td className="px-4 py-3">
                      <Badge tone={statusTone(p.status)}>{p.status}</Badge>
                    </td>
                    <td className="px-4 py-3 text-right">
                      {has('PAYMENT_CREATE') && p.status === 'COMPLETED' ? (
                        <Button
                          variant="outline"
                          onClick={() => {
                            if (confirm('Refund this payment?')) refund.mutate(p.id)
                          }}
                        >
                          Refund
                        </Button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
        {payments.data && payments.data.totalPages > 1 ? (
          <div className="mt-4 flex gap-2">
            <Button variant="outline" disabled={payments.data.first} onClick={() => setPage((n) => Math.max(0, n - 1))}>
              Previous
            </Button>
            <Button variant="outline" disabled={payments.data.last} onClick={() => setPage((n) => n + 1)}>
              Next
            </Button>
          </div>
        ) : null}
      </div>
      {has('PAYMENT_CREATE') ? (
        <Card>
          <h2 className="mb-4 text-sm font-semibold uppercase tracking-wide text-muted">Record payment</h2>
          <form className="space-y-3" onSubmit={form.handleSubmit((v) => record.mutate(v))}>
            <div>
              <Label>Member</Label>
              <MemberPicker value={member} onChange={setMember} />
            </div>
            <div>
              <Label>Membership</Label>
              <Select {...form.register('membershipId')} disabled={!member || memberships.isLoading}>
                <option value="">Not linked to a membership</option>
                {(memberships.data ?? [])
                  .filter((m) => m.status !== 'CANCELLED')
                  .map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.planName} · {m.paymentStatus} · {money(m.amountPaid, m.currency)} of{' '}
                      {money(m.price, m.currency)}
                    </option>
                  ))}
              </Select>
            </div>
            <div>
              <Label>Amount</Label>
              <Input type="number" step="0.01" {...form.register('amount')} />
              <FieldError message={form.formState.errors.amount?.message} />
            </div>
            <div>
              <Label>Currency</Label>
              <Select {...form.register('currency')} disabled={Boolean(selectedMembership)}>
                {currencyOptions.map((code) => (
                  <option key={code} value={code}>
                    {code}
                  </option>
                ))}
              </Select>
            </div>
            <div>
              <Label>Method</Label>
              <Select {...form.register('method')}>
                <option value="CASH">Cash</option>
                <option value="CARD">Card</option>
                <option value="UPI">UPI</option>
                <option value="BANK_TRANSFER">Bank transfer</option>
                <option value="OTHER">Other</option>
              </Select>
            </div>
            <div>
              <Label>Reference</Label>
              <Input {...form.register('reference')} />
            </div>
            <div>
              <Label>Paid on</Label>
              <Input type="date" {...form.register('paidOn')} />
            </div>
            {record.error ? <QueryError error={record.error} /> : null}
            <Button type="submit" disabled={record.isPending || !member}>
              Record
            </Button>
          </form>
        </Card>
      ) : null}
    </div>
  )
}
