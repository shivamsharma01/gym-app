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
  Card,
  EmptyState,
  Field,
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
import { formatDate, money } from '@/lib/cn'
import { statusTone } from '@/lib/status'
import type {
  Member,
  Membership,
  PageResponse,
  Payment,
  PaymentSummary,
} from '@/lib/types'

const schema = z.object({
  amount: z.string().min(1, 'Required'),
  currency: z.string().length(3),
  method: z.string(),
  reference: z.string().optional(),
  paidOn: z.string().optional(),
  notes: z.string().optional(),
  membershipId: z.string().optional(),
})

const months = [
  { value: 1, label: 'April' },
  { value: 2, label: 'May' },
  { value: 3, label: 'June' },
  { value: 4, label: 'July' },
  { value: 5, label: 'August' },
  { value: 6, label: 'September' },
  { value: 7, label: 'October' },
  { value: 8, label: 'November' },
  { value: 9, label: 'December' },
  { value: 10, label: 'January' },
  { value: 11, label: 'February' },
  { value: 12, label: 'March' },
]

const gstRates = [0, 5, 12, 18, 28]

type Form = z.infer<typeof schema>

export function PaymentsPage() {
  const { has } = useAuth()
  const qc = useQueryClient()

  const now = new Date()

  /*
   * Determine the current financial year.
   *
   * If current date is:
   *
   * April 2026 - March 2027
   * then financialYearStart = 2026
   *
   * If current date is:
   *
   * January 2026
   * then financialYearStart = 2025
   *
   * because January 2026 belongs to FY 2025-26.
   */
  const currentFinancialYearStart =
      now.getMonth() >= 3
          ? now.getFullYear()
          : now.getFullYear() - 1

  const [page, setPage] = useState(0)

  const [member, setMember] =
      useState<Member | null>(null)

  const [refundId, setRefundId] =
      useState<string | null>(null)

  /*
   * Month values:
   *
   * 1 = April
   * 2 = May
   * ...
   * 9 = December
   * 10 = January
   * 11 = February
   * 12 = March
   *
   * This makes the month selector naturally follow
   * the Indian financial year.
   */
  const currentFinancialMonth =
      now.getMonth() >= 3
          ? now.getMonth() - 2
          : now.getMonth() + 10

  const [selectedMonth, setSelectedMonth] =
      useState(currentFinancialMonth)

  /*
   * selectedYear represents the START year
   * of the financial year.
   *
   * Example:
   *
   * selectedYear = 2026
   *
   * means:
   *
   * FY 2026-27
   * 01-Apr-2026 -> 31-Mar-2027
   */
  const [selectedYear, setSelectedYear] =
      useState(currentFinancialYearStart)

  const [gstRate, setGstRate] = useState(18)

  /*
   * Generate financial years.
   *
   * Example:
   *
   * FY 2026-27
   * FY 2025-26
   * FY 2024-25
   * ...
   */
  const financialYears = Array.from(
      { length: 6 },
      (_, index) => {
        const startYear =
            currentFinancialYearStart - index

        return {
          value: startYear,
          label: `FY ${startYear}-${String(
              startYear + 1,
          ).slice(-2)}`,
        }
      },
  )

  /*
   * Determine the actual calendar year for
   * the selected month.
   *
   * Financial year:
   *
   * April 2026 -> selectedYear = 2026
   * May 2026   -> selectedYear = 2026
   * ...
   * December 2026 -> selectedYear = 2026
   *
   * January 2027 -> selectedYear + 1
   * February 2027 -> selectedYear + 1
   * March 2027 -> selectedYear + 1
   */
  const selectedCalendarMonth =
      selectedMonth <= 9
          ? selectedMonth + 3
          : selectedMonth - 9

  const selectedCalendarYear =
      selectedMonth <= 9
          ? selectedYear
          : selectedYear + 1

  /*
   * JS Date uses:
   *
   * January = 0
   * February = 1
   * ...
   * December = 11
   */

  const monthStartDate = new Date(
      selectedCalendarYear,
      selectedCalendarMonth - 1,
      1,
  )

  const monthEndDate = new Date(
      selectedCalendarYear,
      selectedCalendarMonth,
      0,
  )

  const monthStart =
      `${selectedCalendarYear}-${String(
          selectedCalendarMonth,
      ).padStart(2, '0')}-01`

  const monthEnd =
      `${selectedCalendarYear}-${String(
          selectedCalendarMonth,
      ).padStart(2, '0')}-${String(
          monthEndDate.getDate(),
      ).padStart(2, '0')}`

  /*
   * Financial year date range.
   *
   * FY 2026-27:
   *
   * 01-Apr-2026
   *     ->
   * 31-Mar-2027
   */
  const financialYearStart =
      `${selectedYear}-04-01`

  const financialYearEnd =
      `${selectedYear + 1}-03-31`

  /*
   * Monthly payment summary.
   */
  const monthlySummary = useQuery({
    queryKey: [
      'payments',
      'summary',
      'monthly',
      selectedYear,
      selectedMonth,
    ],

    queryFn: () =>
        api<PaymentSummary>(
            `/api/v1/payments/summary?from=${monthStart}&to=${monthEnd}`,
        ),
  })

  /*
   * Financial year payment summary.
   */
  const annualSummary = useQuery({
    queryKey: [
      'payments',
      'summary',
      'financial-year',
      selectedYear,
    ],

    queryFn: () =>
        api<PaymentSummary>(
            `/api/v1/payments/summary?from=${financialYearStart}&to=${financialYearEnd}`,
        ),
  })

  /*
   * Payment list.
   *
   * Only payments belonging to the selected
   * financial-year month are displayed.
   */
  const payments = useQuery({
    queryKey: [
      'payments',
      page,
      selectedYear,
      selectedMonth,
    ],

    queryFn: () =>
        api<PageResponse<Payment>>(
            `/api/v1/payments?page=${page}&size=20&from=${monthStart}&to=${monthEnd}`,
        ),
  })

  /*
   * Payment totals.
   */
  const monthlyTotal =
      Number(
          monthlySummary.data?.totalAmount ?? 0,
      )

  const annualTotal =
      Number(
          annualSummary.data?.totalAmount ?? 0,
      )

  /*
   * GST calculation.
   *
   * Payment amount is considered GST-inclusive.
   *
   * Example:
   *
   * Total = ₹11,800
   * GST = 18%
   *
   * Taxable = ₹11,800 / 1.18
   *         = ₹10,000
   *
   * GST = ₹1,800
   */
  const gstMultiplier =
      1 + gstRate / 100

  const monthlyTaxable =
      monthlyTotal / gstMultiplier

  const monthlyGst =
      monthlyTotal - monthlyTaxable

  const annualTaxable =
      annualTotal / gstMultiplier

  const annualGst =
      annualTotal - annualTaxable

  const roundMoney = (
      value: number,
  ) =>
      Math.round(
          (value + Number.EPSILON) * 100,
      ) / 100

  const monthlyTaxableAmount =
      roundMoney(monthlyTaxable)

  const monthlyGstAmount =
      roundMoney(monthlyGst)

  const annualTaxableAmount =
      roundMoney(annualTaxable)

  const annualGstAmount =
      roundMoney(annualGst)

  /*
   * Payment form.
   */
  const form = useForm<Form>({
    resolver: zodResolver(schema),

    defaultValues: {
      amount: '',
      currency: 'INR',
      method: 'CASH',
      reference: '',
      paidOn: '',
      notes: '',
      membershipId: '',
    },
  })

  /*
   * Memberships for selected member.
   */
  const memberships = useQuery({
    queryKey: [
      'memberships',
      member?.id,
    ],

    queryFn: () =>
        api<Membership[]>(
            `/api/v1/members/${member!.id}/memberships`,
        ),

    enabled: Boolean(member),
  })

  const membershipId =
      form.watch('membershipId')

  const selectedMembership =
      memberships.data?.find(
          (m) => m.id === membershipId,
      )

  /*
   * Automatically select unpaid membership.
   */
  useEffect(() => {
    if (!member) {
      form.setValue(
          'membershipId',
          '',
      )

      form.setValue(
          'amount',
          '',
      )

      form.setValue(
          'currency',
          'INR',
      )

      return
    }

    if (!memberships.data) return

    const unpaid =
        memberships.data.find(
            (m) =>
                m.status !== 'CANCELLED' &&
                m.paymentStatus !== 'PAID',
        )

    form.setValue(
        'membershipId',
        unpaid?.id ?? '',
    )
  }, [
    member,
    memberships.data,
    form,
  ])

  /*
   * Automatically set remaining amount.
   */
  useEffect(() => {
    if (!selectedMembership) return

    const remaining =
        Number(selectedMembership.price) -
        Number(
            selectedMembership.amountPaid,
        )

    if (remaining > 0) {
      form.setValue(
          'amount',
          remaining.toFixed(2),
      )
    }

    form.setValue(
        'currency',
        selectedMembership.currency,
    )
  }, [
    selectedMembership,
    form,
  ])

  /*
   * Refund mutation.
   */
  const refund = useMutation({
    mutationFn: (id: string) =>
        api<Payment>(
            `/api/v1/payments/${id}/refund`,
            {
              method: 'POST',
            },
        ),

    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: ['payments'],
      })
    },
  })

  const selectedMonthLabel =
      months.find(
          (month) =>
              month.value === selectedMonth,
      )?.label ?? ''

  const selectedFinancialYear =
      `FY ${selectedYear}-${String(
          selectedYear + 1,
      ).slice(-2)}`

  return (
      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(20rem,26rem)]">
        <div>
          <PageHeader
              title="Payments"
              description="Recorded against members. Refunds require PAYMENT_CREATE."
          />

          {/* ================================================== */}
          {/* Financial Year / Month / GST selector */}
          {/* ================================================== */}

          <Card className="mb-6">
            <div className="grid gap-4 sm:grid-cols-3">

              {/* Financial Year */}

              <Field label="Financial Year">
                <Select
                    value={selectedYear}
                    onChange={(e) => {
                      setSelectedYear(
                          Number(e.target.value),
                      )

                      setPage(0)
                    }}
                >
                  {financialYears.map(
                      (financialYear) => (
                          <option
                              key={
                                financialYear.value
                              }
                              value={
                                financialYear.value
                              }
                          >
                            {financialYear.label}
                          </option>
                      ),
                  )}
                </Select>
              </Field>

              {/* Month */}

              <Field label="Month">
                <Select
                    value={selectedMonth}
                    onChange={(e) => {
                      setSelectedMonth(
                          Number(e.target.value),
                      )

                      setPage(0)
                    }}
                >
                  {months.map((month) => (
                      <option
                          key={month.value}
                          value={month.value}
                      >
                        {month.label}
                      </option>
                  ))}
                </Select>
              </Field>

              {/* GST */}

              <Field label="GST Rate">
                <Select
                    value={gstRate}
                    onChange={(e) => {
                      setGstRate(
                          Number(e.target.value),
                      )
                    }}
                >
                  {gstRates.map((rate) => (
                      <option
                          key={rate}
                          value={rate}
                      >
                        {rate}%
                      </option>
                  ))}
                </Select>
              </Field>

            </div>
          </Card>

          {/* ================================================== */}
          {/* Monthly Payment Summary */}
          {/* ================================================== */}

          <Card className="mb-6">
            <div className="mb-4">
              <h2 className="text-sm font-semibold">
                Monthly Payment Summary
              </h2>

              <p className="mt-1 text-xs text-muted">
                {selectedMonthLabel}{' '}
                {selectedCalendarYear}
              </p>
            </div>

            {monthlySummary.isLoading ? (
                <Skeleton className="h-28" />
            ) : monthlySummary.error ? (
                <QueryError
                    error={
                      monthlySummary.error
                    }
                />
            ) : (
                <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">

                  <div>
                    <div className="text-xs text-muted">
                      Total Collected
                    </div>

                    <div className="mt-1 text-xl font-bold">
                      {money(
                          monthlyTotal,
                          'INR',
                      )}
                    </div>
                  </div>

                  <div>
                    <div className="text-xs text-muted">
                      Taxable Amount
                    </div>

                    <div className="mt-1 text-xl font-bold">
                      {money(
                          monthlyTaxableAmount,
                          'INR',
                      )}
                    </div>
                  </div>

                  <div>
                    <div className="text-xs text-muted">
                      GST ({gstRate}%)
                    </div>

                    <div className="mt-1 text-xl font-bold">
                      {money(
                          monthlyGstAmount,
                          'INR',
                      )}
                    </div>
                  </div>

                  <div>
                    <div className="text-xs text-muted">
                      Payments
                    </div>

                    <div className="mt-1 text-xl font-bold">
                      {
                          monthlySummary.data
                              ?.paymentCount ?? 0
                      }
                    </div>
                  </div>

                </div>
            )}
          </Card>

          {/* ================================================== */}
          {/* Financial Year GST Summary */}
          {/* ================================================== */}

          <Card className="mb-6">
            <div className="mb-4">
              <h2 className="text-sm font-semibold">
                Annual GST Summary
              </h2>

              <p className="mt-1 text-xs text-muted">
                {selectedFinancialYear}
                {' · '}
                {selectedYear}-04-01
                {' → '}
                {selectedYear + 1}-03-31
              </p>
            </div>

            {annualSummary.isLoading ? (
                <Skeleton className="h-28" />
            ) : annualSummary.error ? (
                <QueryError
                    error={
                      annualSummary.error
                    }
                />
            ) : (
                <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">

                  <div>
                    <div className="text-xs text-muted">
                      Annual Collection
                    </div>

                    <div className="mt-1 text-xl font-bold">
                      {money(
                          annualTotal,
                          'INR',
                      )}
                    </div>
                  </div>

                  <div>
                    <div className="text-xs text-muted">
                      Taxable Amount
                    </div>

                    <div className="mt-1 text-xl font-bold">
                      {money(
                          annualTaxableAmount,
                          'INR',
                      )}
                    </div>
                  </div>

                  <div>
                    <div className="text-xs text-muted">
                      GST ({gstRate}%)
                    </div>

                    <div className="mt-1 text-xl font-bold">
                      {money(
                          annualGstAmount,
                          'INR',
                      )}
                    </div>
                  </div>

                  <div>
                    <div className="text-xs text-muted">
                      Payments
                    </div>

                    <div className="mt-1 text-xl font-bold">
                      {
                          annualSummary.data
                              ?.paymentCount ?? 0
                      }
                    </div>
                  </div>

                </div>
            )}
          </Card>

          {/* ================================================== */}
          {/* Payment Table */}
          {/* ================================================== */}

          {payments.isLoading ? (
              <Skeleton className="h-40" />
          ) : null}

          {payments.error ? (
              <QueryError
                  error={payments.error}
              />
          ) : null}

          {payments.data &&
          payments.data.content.length === 0 ? (
              <EmptyState
                  title="No payments"
                  body={`No payments recorded for ${selectedMonthLabel} ${selectedCalendarYear}.`}
              />
          ) : null}

          {payments.data &&
          payments.data.content.length > 0 ? (
              <>
                <div className="mb-3">
                  <h2 className="text-sm font-semibold">
                    Payments
                  </h2>

                  <p className="mt-1 text-xs text-muted">
                    {selectedMonthLabel}{' '}
                    {selectedCalendarYear}
                  </p>
                </div>

                <TableShell>
                  <Table>
                    <THead>
                      <tr>
                        <Th>Paid on</Th>
                        <Th>Amount</Th>
                        <Th>Method</Th>
                        <Th>Status</Th>
                        <Th className="text-right">
                          Actions
                        </Th>
                      </tr>
                    </THead>

                    <tbody>
                    {payments.data.content.map(
                        (p) => (
                            <Tr key={p.id}>
                              <Td>
                                {formatDate(
                                    p.paidOn,
                                )}
                              </Td>

                              <Td className="font-medium tabular-nums">
                                {money(
                                    p.amount,
                                    p.currency,
                                )}
                              </Td>

                              <Td className="text-muted">
                                {p.method}
                              </Td>

                              <Td>
                                <Badge
                                    tone={statusTone(
                                        p.status,
                                    )}
                                >
                                  {p.status}
                                </Badge>
                              </Td>

                              <Td className="text-right">
                                {has(
                                    'PAYMENT_CREATE',
                                ) &&
                                p.status ===
                                'COMPLETED' ? (
                                    <Button
                                        variant="outline"
                                        size="sm"
                                        onClick={() =>
                                            setRefundId(
                                                p.id,
                                            )
                                        }
                                    >
                                      Refund
                                    </Button>
                                ) : null}
                              </Td>
                            </Tr>
                        ),
                    )}
                    </tbody>
                  </Table>
                </TableShell>
              </>
          ) : null}

          {/* ================================================== */}
          {/* Pagination */}
          {/* ================================================== */}

          {payments.data &&
          payments.data.totalPages > 1 ? (
              <div className="mt-4 flex gap-2">
                <Button
                    variant="outline"
                    disabled={
                      payments.data.first
                    }
                    onClick={() =>
                        setPage((n) =>
                            Math.max(0, n - 1),
                        )
                    }
                >
                  Previous
                </Button>

                <Button
                    variant="outline"
                    disabled={
                      payments.data.last
                    }
                    onClick={() =>
                        setPage((n) => n + 1)
                    }
                >
                  Next
                </Button>
              </div>
          ) : null}
        </div>

        {/* ================================================== */}
        {/* Refund Dialog */}
        {/* ================================================== */}

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

              refund.mutate(
                  refundId,
                  {
                    onSettled: () =>
                        setRefundId(null),
                  },
              )
            }}
        />
      </div>
  )
}