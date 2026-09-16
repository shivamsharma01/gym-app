import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { ConfirmDialog } from '@/components/Dialog'
import { QueryError } from '@/components/QueryError'
import {
  Badge,
  Button,
  EmptyState,
  PageHeader,
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
    </div>
  )
}
