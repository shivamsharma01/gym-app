import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { ConfirmDialog } from '@/components/Dialog'
import { MemberPicker } from '@/components/MemberPicker'
import { QueryError } from '@/components/QueryError'
import {
  Badge,
  Button,
  Card,
  EmptyState,
  FieldError,
  Input,
  Label,
  PageHeader,
  Select,
  Skeleton,
  Table,
  TableShell,
  THead,
  Th,
  Td,
  Tr,
} from '@/components/ui'
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
  const [refundId, setRefundId] = useState<string | null>(null)
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
          <TableShell>
            <Table>
              <THead>
                <tr>
                  <Th>Paid on</Th>
                  <Th>Amount</Th>
                  <Th>Method</Th>
                  <Th>Status</Th>
                  <Th className="text-right">Actions</Th>
                </tr>
              </THead>
              <tbody>
                {payments.data.content.map((p) => (
                  <Tr key={p.id}>
                    <Td>{formatDate(p.paidOn)}</Td>
                    <Td className="font-medium tabular-nums">{money(p.amount, p.currency)}</Td>
                    <Td className="text-muted">{p.method}</Td>
                    <Td>
                      <Badge tone={statusTone(p.status)}>{p.status}</Badge>
                    </Td>
                    <Td className="text-right">
                      {has('PAYMENT_CREATE') && p.status === 'COMPLETED' ? (
                        <Button variant="outline" size="sm" onClick={() => setRefundId(p.id)}>
                          Refund
                        </Button>
                      ) : null}
                    </Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
          </TableShell>
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
      <ConfirmDialog
        open={Boolean(refundId)}
        onClose={() => setRefundId(null)}
        title="Refund payment?"
        description="This marks the payment as refunded. Only continue if you have already processed the money return."
        confirmLabel="Refund"
        danger
        busy={refund.isPending}
        onConfirm={() => {
          if (!refundId) return
          refund.mutate(refundId, { onSettled: () => setRefundId(null) })
        }}
      />
      {has('PAYMENT_CREATE') ? (
        <Card>
          <h2 className="mb-1 text-sm font-semibold tracking-tight">Record payment</h2>
          <p className="mb-4 text-xs text-muted">Link to a membership when collecting plan dues.</p>
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
