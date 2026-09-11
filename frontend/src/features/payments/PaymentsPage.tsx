import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { MemberPicker } from '@/components/MemberPicker'
import { Badge, Button, Card, EmptyState, FieldError, Input, Label, PageHeader, Select, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { api } from '@/lib/api'
import { useAuth } from '@/lib/auth'
import { formatDate, money } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type { Member, PageResponse, Payment } from '@/lib/types'

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
    <div className="grid gap-8 lg:grid-cols-[1fr_22rem]">
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
                      {has('PAYMENT_CREATE') && p.status === 'PAID' ? (
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
              <Label>Amount</Label>
              <Input type="number" step="0.01" {...form.register('amount')} />
              <FieldError message={form.formState.errors.amount?.message} />
            </div>
            <div>
              <Label>Currency</Label>
              <Input maxLength={3} {...form.register('currency')} />
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
            <div>
              <Label>Membership id (optional)</Label>
              <Input {...form.register('membershipId')} placeholder="Public id from member page" />
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
