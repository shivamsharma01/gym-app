import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Input,
  Label,
  Select,
} from '@/components/ui';
import { DateField } from '@/components/DateField';
import { QueryError } from '@/components/QueryError';
import { api } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { formatDate, money } from '@/lib/cn';
import { membershipStatusLabel, statusTone } from '@/lib/status';
import type { Membership, PageResponse, Plan } from '@/lib/types';

function todayIso() {
  const d = new Date();
  return toIso(d.getFullYear(), d.getMonth() + 1, d.getDate());
}

function toIso(year: number, month: number, day: number) {
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

function addDays(iso: string, days: number) {
  const [y, m, d] = iso.split('-').map(Number);
  const dt = new Date(y, m - 1, d);
  dt.setDate(dt.getDate() + days);
  return toIso(dt.getFullYear(), dt.getMonth() + 1, dt.getDate());
}

export function MembershipPanel({
  memberId,
  memberStatus,
  memberAuthority,
  rows,
}: {
  memberId: string;
  memberStatus: string;
  memberAuthority?: string;
  rows: Membership[];
}) {
  const { has } = useAuth();
  const qc = useQueryClient();

  const plans = useQuery({
    queryKey: ['plans'],
    queryFn: () => api<PageResponse<Plan>>('/api/v1/plans?size=50'),
    enabled: has('MEMBERSHIP_CREATE') || has('MEMBERSHIP_UPDATE'),
  });

// Create membership
  const [planId, setPlanId] = useState('');
  const [startDate, setStartDate] = useState(todayIso());
  const [endDate, setEndDate] = useState('');
  const [discountAmount, setDiscountAmount] = useState('');
  const [discountConfirmed, setDiscountConfirmed] = useState(false);

  // Edit membership
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editPlanId, setEditPlanId] = useState('');
  const [editStart, setEditStart] = useState('');
  const [editEnd, setEditEnd] = useState('');
  const [editDiscountAmount, setEditDiscountAmount] = useState('');
  const [editDiscountConfirmed, setEditDiscountConfirmed] = useState(false);

  // Renewal
  const [renewingId, setRenewingId] = useState<string | null>(null);
  const [renewPlanId, setRenewPlanId] = useState('');
  const [renewStartDate, setRenewStartDate] = useState('');
  const [renewEndDate, setRenewEndDate] = useState('');
  const [renewDiscountAmount, setRenewDiscountAmount] = useState('');
  const [renewDiscountConfirmed, setRenewDiscountConfirmed] = useState(false);
// Record payment
  const [paymentMembershipId, setPaymentMembershipId] = useState<string | null>(null)
  const [paymentAmount, setPaymentAmount] = useState('')
  const [paymentCurrency, setPaymentCurrency] = useState('INR')
  const [paymentMethod, setPaymentMethod] = useState('CASH')
  const [paymentReference, setPaymentReference] = useState('')
  const [paymentDate, setPaymentDate] = useState(todayIso())
  const selectedPlan = (plans.data?.content ?? []).find((p) => p.id === planId);
  const planAmount = Number(selectedPlan?.price ?? 0);
  const editingMembership = rows.find((row) => row.id === editingId);
  const editingPlan = (plans.data?.content ?? []).find((p) => p.id === editPlanId);
  const editPlanAmount = Number(editingPlan?.price ?? editingMembership?.price ?? 0);
  const editDiscount = Number(editDiscountAmount) || 0;
  const editNetAmount = Math.max(editPlanAmount - editDiscount, 0);
  const editAmountPaid = Number(editingMembership?.amountPaid ?? 0);
  const editRemainingAmount = Math.max(editNetAmount - editAmountPaid, 0);
  const invalidEditDiscount = editDiscount > editPlanAmount;
  const discount = Number(discountAmount) || 0;
  const netAmount = Math.max(planAmount - discount, 0);
  const invalidDiscount = discount > planAmount;
  const renewingMembership = rows.find((row) => row.id === renewingId);
  const renewingPlan = (plans.data?.content ?? []).find(
      (p) => p.id === renewPlanId
  );

  const renewDiscount = Number(renewDiscountAmount) || 0;
  const renewPlanAmount = Number(renewingPlan?.price ?? 0);
  const invalidRenewDiscount = renewDiscount > renewPlanAmount;
  const paymentMembership = rows.find(
      (row) => row.id === paymentMembershipId,
  )
  const paymentUnpaid = paymentMembership
      ? Math.max(Number(paymentMembership.netAmount ?? paymentMembership.price) - Number(paymentMembership.amountPaid ?? 0), 0)
      : 0
  const paymentTooHigh = Number(paymentAmount || 0) > paymentUnpaid + 0.005
  const currentPlan = (plans.data?.content ?? []).find(
    (p) => p.name === renewingMembership?.planName,
  );

  const currentPlanPrice = currentPlan?.price ?? renewingMembership?.price ?? 0;

  const selectedRenewPlan = (plans.data?.content ?? []).find(
    (p) => p.id === renewPlanId,
  );

  function applyPlan(id: string) {
    setPlanId(id);

    const plan = (plans.data?.content ?? []).find((p) => p.id === id);

    const start = startDate || todayIso();

    setStartDate(start);

    if (plan) {
      setEndDate(addDays(start, Math.max(plan.durationDays - 1, 0)));
    }
  }

  function applyStart(next: string) {
    setStartDate(next);

    if (selectedPlan) {
      setEndDate(addDays(next, Math.max(selectedPlan.durationDays - 1, 0)));
    }
  }

  function openEdit(row: Membership) {
    const matchedPlan = (plans.data?.content ?? []).find((p) => p.name === row.planName);

    setEditingId(row.id);
    setEditPlanId(matchedPlan?.id ?? '');
    setEditStart(row.startDate);
    setEditEnd(row.endDate);
    setEditDiscountAmount(String(row.discountAmount ?? 0));
    setEditDiscountConfirmed(false);
  }

  function applyEditPlan(id: string) {
    setEditPlanId(id);

    const plan = (plans.data?.content ?? []).find((p) => p.id === id);
    if (plan && editStart) {
      setEditEnd(addDays(editStart, Math.max(plan.durationDays - 1, 0)));
    }
  }

  function openRenewal(row: Membership) {
    const start = addDays(row.endDate, 1);

    setRenewingId(row.id);
    setRenewPlanId('');
    setRenewStartDate(start);
    setRenewEndDate('');
  }

  function openRecordPayment(row: Membership) {
    setPaymentMembershipId(row.id)

    const remainingAmount = Math.max(
        Number(row.netAmount ?? row.price) - Number(row.amountPaid ?? 0),
        0,
    )

    setPaymentAmount(String(remainingAmount))
    setPaymentCurrency(row.currency || 'INR')
    setPaymentMethod('CASH')
    setPaymentReference('')
    setPaymentDate(todayIso())
  }

  function closeRenewal() {
    setRenewingId(null);
    setRenewPlanId('');
    setRenewStartDate('');
    setRenewEndDate('');
    setRenewDiscountAmount('');
    setRenewDiscountConfirmed(false);
  }

  function applyRenewPlan(id: string) {
    setRenewPlanId(id);

    const plan = (plans.data?.content ?? []).find((p) => p.id === id);

    if (plan && renewStartDate) {
      setRenewEndDate(
        addDays(renewStartDate, Math.max(plan.durationDays - 1, 0)),
      );
    }
  }

  function applyRenewStart(next: string) {
    setRenewStartDate(next);

    if (selectedRenewPlan) {
      setRenewEndDate(
        addDays(next, Math.max(selectedRenewPlan.durationDays - 1, 0)),
      );
    }
  }

  const create = useMutation({
        mutationFn: () => {
          if (invalidDiscount) {
            throw new Error('Discount cannot be greater than the plan amount.');
          }

          return api<Membership>('/api/v1/memberships', {
            method: 'POST',
            body: JSON.stringify({
              memberId,
              planId,
              startDate,
              endDate,
              discountAmount: Number(discountAmount) || 0,
            }),
          });
        },

    onSuccess: () => {
      setPlanId('');
      setStartDate(todayIso());
      setEndDate('');
      setDiscountAmount('');
      setDiscountConfirmed(false);

      void qc.invalidateQueries({
        queryKey: ['memberships', memberId],
      });

      void qc.invalidateQueries({
        queryKey: ['access', memberId],
      });
    },
  });

  const saveMembership = useMutation({
    mutationFn: () => {
      if (invalidEditDiscount) {
        throw new Error('Discount cannot be greater than the plan amount.');
      }

      return api<Membership>(`/api/v1/memberships/${editingId}`, {
        method: 'PUT',
        body: JSON.stringify({
          planId: editPlanId,
          startDate: editStart,
          endDate: editEnd,
          discountAmount: editDiscount,
        }),
      });
    },

    onSuccess: () => {
      setEditingId(null);
      setEditPlanId('');
      setEditStart('');
      setEditEnd('');
      setEditDiscountAmount('');
      setEditDiscountConfirmed(false);

      void qc.invalidateQueries({
        queryKey: ['memberships', memberId],
      });

      void qc.invalidateQueries({
        queryKey: ['access', memberId],
      });
    },
  });

  const act = useMutation({
    mutationFn: ({
      id,
      path,
      body,
    }: {
      id: string;
      path: string;
      body?: unknown;
    }) =>
      api(`/api/v1/memberships/${id}/${path}`, {
        method: 'POST',
        body: body === undefined ? undefined : JSON.stringify(body),
      }),

    onSuccess: () => {
      void qc.invalidateQueries({
        queryKey: ['memberships', memberId],
      });

      void qc.invalidateQueries({
        queryKey: ['access', memberId],
      });
    },
  });

  const renew = useMutation({
        mutationFn: () => {
          if (invalidRenewDiscount) {
            throw new Error('Discount cannot be greater than the plan amount.');
          }

          return api<Membership>(
              `/api/v1/memberships/${renewingId}/renew`,
              {
                method: 'POST',
                body: JSON.stringify({
                  planId: renewPlanId,
                  startDate: renewStartDate,
                  endDate: renewEndDate,
                  discountAmount: Number(renewDiscountAmount) || 0,
                }),
              },
          );
        },

    onSuccess: () => {
      closeRenewal();

      void qc.invalidateQueries({
        queryKey: ['memberships', memberId],
      });

      void qc.invalidateQueries({
        queryKey: ['access', memberId],
      });
    },
  });

  const recordPayment = useMutation({
    mutationFn: () =>
        api('/api/v1/payments', {
          method: 'POST',
          body: JSON.stringify({
            memberId,
            membershipId: paymentMembershipId,
            amount: Number(paymentAmount),
            currency: paymentCurrency,
            method: paymentMethod,
            reference: paymentReference || null,
            paidOn: paymentDate,
          }),
        }),

    onSuccess: () => {
      setPaymentMembershipId(null)
      setPaymentAmount('')
      setPaymentCurrency('INR')
      setPaymentMethod('CASH')
      setPaymentReference('')
      setPaymentDate(todayIso())

      void qc.invalidateQueries({
        queryKey: ['memberships', memberId],
      })

      void qc.invalidateQueries({
        queryKey: ['access', memberId],
      })
    },
  })

  const activePlans = (plans.data?.content ?? []).filter(
    (p) => p.status === 'ACTIVE',
  );

  const memberStatusIsInactive = memberStatus === 'INACTIVE';

  return (
    <div className="space-y-4">
      {/* CREATE MEMBERSHIP */}
      {has('MEMBERSHIP_CREATE') ? (
        memberStatusIsInactive ? (
          <div className="rounded-xl border border-warn/40 bg-warn/10 px-4 py-3 text-sm text-warn">
            Account is inactive. Create, edit, renew, freeze, cancel, delete, and payment actions are disabled. Reactivate the account first.
          </div>
        ) : activePlans.length === 0 ? (
          <p className="text-sm text-muted">
            No plans yet.{' '}
            <Link className="underline" to="/app/plans">
              Create a plan
            </Link>{' '}
            (name, price, duration), then come back here to start it for this
            member.
          </p>
        ) : (
          <div className="space-y-3 rounded-xl border border-line p-3">
            <Select value={planId} onChange={(e) => applyPlan(e.target.value)}>
              <option value="">Select a plan</option>

              {activePlans
                .filter((p) => (p.durationDays ?? 0) < 3650 || memberAuthority === 'ADMIN')
                .map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} · {p.durationDays} days
                </option>
              ))}
            </Select>
            <div>
              <Label htmlFor="membership-discount">Discount</Label>
              <Input
                  id="membership-discount"
                  type="number"
                  min="0"
                  step="0.01"
                  value={discountAmount}
                  onChange={(e) => setDiscountAmount(e.target.value)}
                  placeholder="0"
                  aria-invalid={invalidDiscount}
                  disabled={!planId}
              />
              {invalidDiscount ? (
                  <p className="mt-1 text-sm text-red-600">
                    Discount cannot be greater than the plan amount.
                  </p>
              ) : null}
            </div>
            {planId ? (
                <div className="rounded-lg border border-line bg-surface p-3">
                  <div className="flex justify-between text-sm">
                    <span>Plan Amount</span>
                    <span>₹{planAmount.toFixed(2)}</span>
                  </div>

                  <div className="mt-1 flex justify-between text-sm">
                    <span>Discount</span>
                    <span>- ₹{discount.toFixed(2)}</span>
                  </div>

                  <div className="mt-2 flex justify-between border-t border-line pt-2 font-semibold">
                    <span>Net Amount</span>
                    <span>₹{netAmount.toFixed(2)}</span>
                  </div>
                </div>
            ) : null}
            {planId ? (
              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <Label htmlFor="membership-start">Start</Label>

                  <DateField
                    id="membership-start"
                    value={startDate}
                    onChange={applyStart}
                    ariaLabel="Membership start date"
                  />
                </div>

                <div>
                  <Label htmlFor="membership-end">End</Label>

                  <DateField
                    id="membership-end"
                    value={endDate}
                    min={startDate}
                    onChange={setEndDate}
                    ariaLabel="Membership end date"
                  />
                </div>
              </div>
            ) : null}

            <Button
                disabled={
                    !planId ||
                    !startDate ||
                    !endDate ||
                    invalidDiscount ||
                    create.isPending
                }
              onClick={() => {
                const discount = Number(discountAmount) || 0;

                if (discount > 0 && !discountConfirmed) {
                  const confirmed = window.confirm(
                      `You are applying a ₹${discount.toFixed(2)} discount.\n\n` +
                      `Please confirm that this is an approved discount and not an unpaid balance.\n\n` +
                      `Do you want to record this discount?`,
                  );

                  if (!confirmed) {
                    return;
                  }

                  setDiscountConfirmed(true);
                }

                create.mutate();
              }}
            >
              Start membership
            </Button>
          </div>
        )
      ) : null}

      {plans.error ? <QueryError error={plans.error} /> : null}

      {create.error ? <QueryError error={create.error} /> : null}

      {saveMembership.error ? <QueryError error={saveMembership.error} /> : null}

      {act.error ? <QueryError error={act.error} /> : null}

      {renew.error ? <QueryError error={renew.error} /> : null}

      {!rows.length ? (
        <EmptyState
          title="No memberships"
          body="Start a plan for this member."
        />
      ) : null}

      {/* MEMBERSHIPS */}
      {[...rows]
          .sort((a, b) => {
            const order = {
              ACTIVE: 1,
              PENDING: 2,
              FROZEN: 3,
              CANCELLED: 4,
              EXPIRED: 5,
            }

            return (
                (order[a.effectiveStatus as keyof typeof order] ?? 99) -
                (order[b.effectiveStatus as keyof typeof order] ?? 99)
            )
          })
          .map((row) => (
              <Card
                  key={row.id}
                  className={`space-y-2 ${
                      row.effectiveStatus === 'ACTIVE'
                          ? 'border-ok/60 bg-ok/5 ring-1 ring-ok/20'
                          : ''
                  }`}
              >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="font-semibold">{row.planName}</div>

            <Badge tone={statusTone(row.effectiveStatus)}>
              {membershipStatusLabel(row.effectiveStatus)}
            </Badge>
          </div>

                <p className="text-sm text-muted">
                  {formatDate(row.startDate)} → {formatDate(row.endDate)} ·{' '}
                  {money(row.price, row.currency)} · discount{' '}
                  {money(row.discountAmount ?? 0, row.currency)} · paid{' '}
                  {money(row.amountPaid, row.currency)} · unpaid{' '}
                  {money(
                      Math.max(
                          Number(row.netAmount ?? row.price) - Number(row.amountPaid ?? 0),
                          0,
                      ),
                      row.currency,
                  )}{' '}
                  · device {row.deviceSyncState}
                </p>


          {/* EDIT MEMBERSHIP */}
          {editingId === row.id && !memberStatusIsInactive ? (
            <div className="grid gap-3 rounded-xl border border-line bg-raised/30 p-4 sm:grid-cols-2">
              <div className="sm:col-span-2">
                <Label>Plan</Label>
                <Select
                  value={editPlanId}
                  onChange={(e) => applyEditPlan(e.target.value)}
                >
                  <option value="">Select plan</option>
                  {(plans.data?.content ?? []).map((plan) => (
                    <option key={plan.id} value={plan.id}>
                      {plan.name} · {money(plan.price, plan.currency)} · {plan.durationDays} days
                    </option>
                  ))}
                </Select>
              </div>

              <div>
                <Label htmlFor={`edit-start-${row.id}`}>Start</Label>
                <DateField
                  id={`edit-start-${row.id}`}
                  value={editStart}
                  onChange={setEditStart}
                  ariaLabel="Membership start date"
                />
              </div>

              <div>
                <Label htmlFor={`edit-end-${row.id}`}>End</Label>
                <DateField
                  id={`edit-end-${row.id}`}
                  value={editEnd}
                  min={editStart}
                  onChange={setEditEnd}
                  ariaLabel="Membership end date"
                />
              </div>

              <div>
                <Label>Discount</Label>
                <Input
                  type="number"
                  min="0"
                  step="0.01"
                  value={editDiscountAmount}
                  onChange={(e) => setEditDiscountAmount(e.target.value)}
                  aria-invalid={invalidEditDiscount}
                />
                {invalidEditDiscount ? (
                  <p className="mt-1 text-xs text-danger">Discount cannot be greater than the plan amount.</p>
                ) : null}
              </div>

              <div className="rounded-lg border border-line bg-panel px-3 py-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-muted">Plan amount</span>
                  <span>{money(editPlanAmount, editingPlan?.currency ?? editingMembership?.currency ?? 'INR')}</span>
                </div>
                <div className="mt-1 flex justify-between">
                  <span className="text-muted">Amount paid</span>
                  <span>{money(editAmountPaid, editingPlan?.currency ?? editingMembership?.currency ?? 'INR')}</span>
                </div>
                <div className="mt-1 flex justify-between font-semibold">
                  <span>Remaining</span>
                  <span>{money(editRemainingAmount, editingPlan?.currency ?? editingMembership?.currency ?? 'INR')}</span>
                </div>
              </div>

              <div className="flex gap-2 sm:col-span-2">
                <Button
                  disabled={
                    saveMembership.isPending ||
                    !editPlanId ||
                    !editStart ||
                    !editEnd ||
                    invalidEditDiscount
                  }
                  onClick={() => {
                    if (editDiscount > 0 && !editDiscountConfirmed) {
                      const confirmed = window.confirm(
                        `You are applying a ${money(editDiscount, editingPlan?.currency ?? editingMembership?.currency ?? 'INR')} discount.\n\nDo you want to record this discount?`,
                      );
                      if (!confirmed) return;
                      setEditDiscountConfirmed(true);
                    }
                    saveMembership.mutate();
                  }}
                >
                  {saveMembership.isPending ? 'Saving...' : 'Save changes'}
                </Button>

                <Button
                  variant="ghost"
                  type="button"
                  onClick={() => {
                    setEditingId(null);
                    setEditPlanId('');
                    setEditStart('');
                    setEditEnd('');
                    setEditDiscountAmount('');
                    setEditDiscountConfirmed(false);
                  }}
                >
                  Cancel
                </Button>
              </div>
            </div>
          ) : null}

          {/* ACTIONS */}
          {editingId !== row.id && !memberStatusIsInactive ? (
          <div className="flex flex-wrap gap-2">
            {has('MEMBERSHIP_UPDATE') &&
            !memberStatusIsInactive &&
            row.status !== 'CANCELLED' &&
            row.status !== 'FROZEN' &&
            editingId !== row.id ? (
              <Button
                variant="outline"
                onClick={() => openEdit(row)}
              >
                Edit
              </Button>
            ) : null}

            {has('MEMBERSHIP_FREEZE') && !memberStatusIsInactive && row.effectiveStatus === 'ACTIVE' ? (
              <Button
                variant="outline"
                onClick={() =>
                  act.mutate({
                    id: row.id,
                    path: 'freeze',
                  })
                }
              >
                Freeze
              </Button>
            ) : null}

            {has('MEMBERSHIP_FREEZE') && !memberStatusIsInactive && row.status === 'FROZEN' ? (
              <Button
                variant="outline"
                onClick={() =>
                  act.mutate({
                    id: row.id,
                    path: 'unfreeze',
                  })
                }
              >
                Unfreeze
              </Button>
            ) : null}

            {/* RENEW */}
            {has('MEMBERSHIP_CREATE') &&
            !memberStatusIsInactive &&
            (row.effectiveStatus === 'ACTIVE' || row.effectiveStatus === 'EXPIRED') ? (
              <Button
                variant="outline"
                type="button"
                className="border-warn text-warn hover:bg-warn hover:text-accent-ink"
                onClick={() => openRenewal(row)}
              >
                Renew
              </Button>
            ) : null}

            {has('PAYMENT_CREATE') &&
            !memberStatusIsInactive &&
            row.paymentStatus !== 'PAID' &&
            (row.effectiveStatus === 'ACTIVE' || row.effectiveStatus === 'PENDING') ? (
                <Button
                    variant="outline"
                    type="button"
                    className="border-ok text-ok hover:bg-ok hover:text-accent-ink"
                    onClick={(e) => {
                      e.preventDefault()
                      e.stopPropagation()
                      openRecordPayment(row)
                    }}
                >
                  Record Payment
                </Button>
            ) : null}

            {/* CANCEL */}
            {has('MEMBERSHIP_CANCEL') && !memberStatusIsInactive && row.status !== 'CANCELLED' ? (
              <Button
                variant="danger"
                type="button"
                onClick={() => {
                  const reason =
                    window.prompt('Cancel reason (optional)');
                  if(reason === null) return;

                  act.mutate({
                    id: row.id,
                    path: 'cancel',
                    body: { reason },
                  });
                }}
              >
                Cancel
              </Button>
            ) : null}
          </div>
          ) : null}
        </Card>
      ))}

      {/* RENEWAL MODAL */}
      {renewingId && !memberStatusIsInactive && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-canvas/80 p-4">
          <Card className="w-full max-w-md overflow-hidden border border-line bg-panel text-ink shadow-2xl">
            {/* Header */}
            <div className="border-b border-line bg-raised px-6 py-5">
              <h2 className="text-lg font-semibold">Renew Membership</h2>

              <p className="mt-1 text-sm text-muted">
                Select the plan for the next membership period.
              </p>
            </div>

            {/* Body */}
            <div className="space-y-5 px-6 py-5">
              {/* Current Plan */}
              {renewingMembership && (
                <div className="rounded-lg border border-line bg-raised p-4">
                  <div className="mb-2 text-xs font-medium uppercase tracking-wide text-muted">
                    Current Membership
                  </div>

                  <div className="flex items-center justify-between">
                    <div>
                      <div className="font-medium text-ink">
                        {renewingMembership.planName}
                      </div>

                      <div className="mt-1 text-sm text-muted">
                        {formatDate(renewingMembership.startDate)} →{' '}
                        {formatDate(renewingMembership.endDate)}
                      </div>
                    </div>

                    <div className="font-semibold text-ink">
                      {money(currentPlanPrice, renewingMembership.currency)}
                    </div>
                  </div>
                </div>
              )}

              {/* New Plan */}
              <div className="space-y-2">
                <Label
                  htmlFor="renew-plan"
                  className="text-sm font-medium text-ink"
                >
                  New Plan
                </Label>

                <Select
                  id="renew-plan"
                  value={renewPlanId}
                  onChange={(e) => applyRenewPlan(e.target.value)}
                  className="border-line bg-canvas text-ink"
                >
                  <option value="">Select plan</option>

                  {activePlans
                    .filter((plan) => (plan.durationDays ?? 0) < 3650 || memberAuthority === 'ADMIN')
                    .map((plan) => (
                    <option key={plan.id} value={plan.id}>
                      {plan.name} — {money(plan.price, plan.currency)}
                    </option>
                  ))}
                </Select>
                <div>
                  <Label htmlFor="renew-discount">Discount</Label>

                  <Input
                      id="renew-discount"
                      type="number"
                      min="0"
                      step="0.01"
                      value={renewDiscountAmount}
                      onChange={(e) => setRenewDiscountAmount(e.target.value)}
                      placeholder="0"
                      aria-invalid={invalidRenewDiscount}
                      disabled={!renewPlanId}
                  />

                  {invalidRenewDiscount ? (
                      <p className="mt-1 text-sm text-red-600">
                        Discount cannot be greater than the plan amount.
                      </p>
                  ) : null}
                </div>
              </div>

              {/* Dates */}
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label
                    htmlFor="renew-start"
                    className="text-sm text-ink"
                  >
                    Start Date
                  </Label>

                  <DateField
                      id="renew-start"
                      value={renewStartDate}
                      min={renewingMembership ? addDays(renewingMembership.endDate, 1) : undefined}
                      onChange={applyRenewStart}
                      ariaLabel="Renewal start date"
                  />
                </div>

                <div className="space-y-2">
                  <Label htmlFor="renew-end" className="text-sm text-ink">
                    End Date
                  </Label>

                  <DateField
                    id="renew-end"
                    value={renewEndDate}
                    min={renewStartDate}
                    onChange={setRenewEndDate}
                    ariaLabel="Renewal end date"
                  />
                </div>
              </div>

              {/* Error */}
              {renew.error && (
                <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-400">
                  {renew.error instanceof Error
                    ? renew.error.message
                    : 'Unable to renew membership.'}
                </div>
              )}
            </div>

            {/* Footer */}
            <div className="flex justify-end gap-3 border-t border-line bg-raised px-6 py-4">
              <Button
                variant="outline"
                type="button"
                className="border-line bg-transparent text-ink hover:bg-raised"
                onClick={closeRenewal}
              >
                Cancel
              </Button>

              <Button
                type="button"
                disabled={
                    !renewPlanId ||
                    !renewStartDate ||
                    !renewEndDate ||
                    invalidRenewDiscount ||
                    renew.isPending
                }
                className="bg-warn text-accent-ink hover:brightness-110 disabled:opacity-40"
                onClick={() => {
                  const discount = Number(renewDiscountAmount) || 0;

                  if (discount > 0 && !renewDiscountConfirmed) {
                    const confirmed = window.confirm(
                        `You are applying a ₹${discount.toFixed(2)} discount.\n\n` +
                        `Please confirm that this is an approved discount and not an unpaid balance.`,
                    );

                    if (!confirmed) return;

                    setRenewDiscountConfirmed(true);
                  }
                  renew.mutate();
                }}
              >
                {renew.isPending ? 'Renewing...' : 'Renew Membership'}
              </Button>
            </div>
          </Card>
        </div>
      )}

      {/* RECORD PAYMENT MODAL */}
      {paymentMembershipId && paymentMembership ? (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-canvas/80 p-4">
            <Card className="w-full max-w-md overflow-hidden border border-line bg-panel text-ink shadow-2xl">
              <div className="border-b border-line px-6 py-4">
                <h2 className="text-lg font-semibold">Record Payment</h2>
                <p className="mt-1 text-sm text-muted">
                  {paymentMembership.planName}
                </p>
              </div>

              <div className="space-y-4 px-6 py-5">
                <div className="rounded-lg border border-line bg-canvas p-3 text-sm">
                  <div className="flex justify-between">
                    <span>Plan Amount</span>
                    <span>
      {money(
          Number(paymentMembership.price ?? 0),
          paymentMembership.currency,
      )}
    </span>
                  </div>

                  <div className="mt-1 flex justify-between">
                    <span>Discount</span>
                    <span>
      - {money(
                        Number(paymentMembership.discountAmount ?? 0),
                        paymentMembership.currency,
                    )}
    </span>
                  </div>

                  <div className="mt-1 flex justify-between">
                    <span>Net Amount</span>
                    <span>
      {money(
          Number(
              paymentMembership.netAmount ??
              Number(paymentMembership.price ?? 0) -
              Number(paymentMembership.discountAmount ?? 0),
          ),
          paymentMembership.currency,
      )}
    </span>
                  </div>

                  <div className="mt-1 flex justify-between">
                    <span>Already Paid</span>
                    <span>
      {money(
          Number(paymentMembership.amountPaid ?? 0),
          paymentMembership.currency,
      )}
    </span>
                  </div>

                  <div className="mt-2 flex justify-between border-t border-line pt-2 font-semibold">
                    <span>Remaining</span>
                    <span>
    {money(
        Math.max(
            Number(
                paymentMembership.netAmount ??
                Number(paymentMembership.price ?? 0) -
                Number(paymentMembership.discountAmount ?? 0),
            ) - Number(paymentMembership.amountPaid ?? 0),
            0,
        ),
        paymentMembership.currency,
    )}
  </span>
                  </div>

                  <div className="mt-1 flex justify-between text-xs text-muted">
                    <span>After this payment</span>
                    <span>
    {money(
        Math.max(
            Number(
                paymentMembership.netAmount ??
                Number(paymentMembership.price ?? 0) -
                Number(paymentMembership.discountAmount ?? 0),
            ) -
            Number(paymentMembership.amountPaid ?? 0) -
            Number(paymentAmount || 0),
            0,
        ),
        paymentMembership.currency,
    )}
  </span>
                  </div>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="payment-amount">Amount</Label>
                  <Input
                      id="payment-amount"
                      type="number"
                      min="0"
                      max={paymentUnpaid}
                      step="0.01"
                      value={paymentAmount}
                      onChange={(e) => setPaymentAmount(e.target.value)}
                      className="border-line bg-canvas text-ink"
                  />
                  {paymentTooHigh ? (
                      <p className="text-xs text-danger">
                        Cannot be more than the unpaid balance of {money(paymentUnpaid, paymentMembership.currency)}.
                      </p>
                  ) : null}
                </div>

                <div className="space-y-2">
                  <Label htmlFor="payment-currency">Currency</Label>
                  <Select
                      id="payment-currency"
                      value={paymentCurrency}
                      onChange={(e) => setPaymentCurrency(e.target.value)}
                      className="border-line bg-canvas text-ink"
                  >
                    <option value="INR">INR</option>
                    <option value="USD">USD</option>
                  </Select>
                </div>

                <div className="space-y-2">
                  <Label htmlFor="payment-method">Payment Method</Label>
                  <Select
                      id="payment-method"
                      value={paymentMethod}
                      onChange={(e) => setPaymentMethod(e.target.value)}
                      className="border-line bg-canvas text-ink"
                  >
                    <option value="CASH">Cash</option>
                    <option value="UPI">UPI</option>
                    <option value="CARD">Card</option>
                    <option value="BANK_TRANSFER">Bank Transfer</option>
                  </Select>
                </div>

                <div className="space-y-2">
                  <Label htmlFor="payment-reference">Reference</Label>
                  <Input
                      id="payment-reference"
                      value={paymentReference}
                      onChange={(e) => setPaymentReference(e.target.value)}
                      placeholder="Optional"
                      className="border-line bg-canvas text-ink"
                  />
                </div>

                <div className="space-y-2">
                  <Label htmlFor="payment-date">Paid On</Label>
                  <DateField
                      id="payment-date"
                      value={paymentDate}
                      max={todayIso()}
                      onChange={setPaymentDate}
                      ariaLabel="Payment date"
                  />
                </div>

                {recordPayment.error ? (
                    <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-400">
                      {recordPayment.error instanceof Error
                          ? recordPayment.error.message
                          : 'Unable to record payment.'}
                    </div>
                ) : null}
              </div>

              <div className="flex justify-end gap-3 border-t border-line bg-raised px-6 py-4">
                <Button
                    variant="outline"
                    type="button"
                    className="border-line bg-transparent text-ink hover:bg-raised"
                    onClick={() => setPaymentMembershipId(null)}
                >
                  Cancel
                </Button>

                <Button
                    type="button"
                    disabled={
                        recordPayment.isPending ||
                        !paymentAmount ||
                        Number(paymentAmount) <= 0 ||
                        paymentTooHigh ||
                        !paymentDate
                    }
                    className="bg-ok text-accent-ink hover:brightness-110 disabled:opacity-40"
                    onClick={() => recordPayment.mutate()}
                >
                  {recordPayment.isPending ? 'Recording...' : 'Record Payment'}
                </Button>
              </div>
            </Card>
          </div>
      ) : null}
    </div>
  );
}
