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
import { QueryError } from '@/components/QueryError';
import { api } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { formatDate, money } from '@/lib/cn';
import { statusTone } from '@/lib/status';
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
  rows,
}: {
  memberId: string;
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

  // Edit membership dates
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editStart, setEditStart] = useState('');
  const [editEnd, setEditEnd] = useState('');

  // Renewal
  const [renewingId, setRenewingId] = useState<string | null>(null);
  const [renewPlanId, setRenewPlanId] = useState('');
  const [renewStartDate, setRenewStartDate] = useState('');
  const [renewEndDate, setRenewEndDate] = useState('');

  const selectedPlan = (plans.data?.content ?? []).find((p) => p.id === planId);

  const renewingMembership = rows.find((row) => row.id === renewingId);

  const currentPlan = (plans.data?.content ?? []).find(
    (p) => p.name === renewingMembership?.planName,
  );

  const currentPlanPrice = currentPlan?.price ?? renewingMembership?.price ?? 0;

  const selectedRenewPlan = (plans.data?.content ?? []).find(
    (p) => p.id === renewPlanId,
  );

  const additionalAmount =
    selectedRenewPlan && renewingMembership
      ? renewStartDate === renewingMembership.startDate ||
        renewStartDate === addDays(renewingMembership.endDate, 1)
        ? Math.max(selectedRenewPlan.price - currentPlanPrice, 0)
        : selectedRenewPlan.price
      : 0;

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

  function openRenewal(row: Membership) {
    const today = todayIso();

    const start =
      row.endDate >= today ? row.startDate : addDays(row.endDate, 1);

    setRenewingId(row.id);
    setRenewPlanId('');
    setRenewStartDate(start);
    setRenewEndDate('');
  }

  function closeRenewal() {
    setRenewingId(null);
    setRenewPlanId('');
    setRenewStartDate('');
    setRenewEndDate('');
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
    mutationFn: () =>
      api<Membership>('/api/v1/memberships', {
        method: 'POST',
        body: JSON.stringify({
          memberId,
          planId,
          startDate,
          endDate,
        }),
      }),

    onSuccess: () => {
      setPlanId('');
      setStartDate(todayIso());
      setEndDate('');

      void qc.invalidateQueries({
        queryKey: ['memberships', memberId],
      });

      void qc.invalidateQueries({
        queryKey: ['access', memberId],
      });
    },
  });

  const saveDates = useMutation({
    mutationFn: () =>
      api<Membership>(`/api/v1/memberships/${editingId}/dates`, {
        method: 'PUT',
        body: JSON.stringify({
          startDate: editStart,
          endDate: editEnd,
        }),
      }),

    onSuccess: () => {
      setEditingId(null);

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

  const deleteMembership = useMutation({
    mutationFn: (id: string) =>
      api(`/api/v1/memberships/${id}`, {
        method: 'DELETE',
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
    mutationFn: () =>
      api<Membership>(`/api/v1/memberships/${renewingId}/renew`, {
        method: 'POST',
        body: JSON.stringify({
          planId: renewPlanId,
          startDate: renewStartDate,
          endDate: renewEndDate,
        }),
      }),

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

  const activePlans = (plans.data?.content ?? []).filter(
    (p) => p.status === 'ACTIVE',
  );

  return (
    <div className="space-y-4">
      {/* CREATE MEMBERSHIP */}
      {has('MEMBERSHIP_CREATE') ? (
        activePlans.length === 0 ? (
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

              {activePlans.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} · {p.durationDays} days
                </option>
              ))}
            </Select>

            {planId ? (
              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <Label htmlFor="membership-start">Start</Label>

                  <Input
                    id="membership-start"
                    type="date"
                    value={startDate}
                    onChange={(e) => applyStart(e.target.value)}
                  />
                </div>

                <div>
                  <Label htmlFor="membership-end">End</Label>

                  <Input
                    id="membership-end"
                    type="date"
                    value={endDate}
                    min={startDate}
                    onChange={(e) => setEndDate(e.target.value)}
                  />
                </div>
              </div>
            ) : null}

            <Button
              disabled={!planId || !startDate || !endDate || create.isPending}
              onClick={() => create.mutate()}
            >
              Start membership
            </Button>
          </div>
        )
      ) : null}

      {plans.error ? <QueryError error={plans.error} /> : null}

      {create.error ? <QueryError error={create.error} /> : null}

      {saveDates.error ? <QueryError error={saveDates.error} /> : null}

      {act.error ? <QueryError error={act.error} /> : null}

      {deleteMembership.error ? (
        <QueryError error={deleteMembership.error} />
      ) : null}

      {renew.error ? <QueryError error={renew.error} /> : null}

      {!rows.length ? (
        <EmptyState
          title="No memberships"
          body="Start a plan for this member."
        />
      ) : null}

      {/* MEMBERSHIPS */}
      {rows.map((row) => (
        <Card key={row.id} className="space-y-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="font-semibold">{row.planName}</div>

            <Badge tone={statusTone(row.effectiveStatus)}>
              {row.effectiveStatus}
            </Badge>
          </div>

          <p className="text-sm text-muted">
            {formatDate(row.startDate)} → {formatDate(row.endDate)} ·{' '}
            {money(row.price, row.currency)} · paid{' '}
            {money(row.amountPaid, row.currency)} · device {row.deviceSyncState}
          </p>

          {/* CHANGE DATES */}
          {editingId === row.id ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <Label>Start</Label>

                <Input
                  type="date"
                  value={editStart}
                  onChange={(e) => setEditStart(e.target.value)}
                />
              </div>

              <div>
                <Label>End</Label>

                <Input
                  type="date"
                  value={editEnd}
                  min={editStart}
                  onChange={(e) => setEditEnd(e.target.value)}
                />
              </div>

              <div className="flex gap-2 sm:col-span-2">
                <Button
                  disabled={saveDates.isPending || !editStart || !editEnd}
                  onClick={() => saveDates.mutate()}
                >
                  Save dates
                </Button>

                <Button
                  variant="ghost"
                  type="button"
                  onClick={() => setEditingId(null)}
                >
                  Cancel
                </Button>
              </div>
            </div>
          ) : null}

          {/* ACTIONS */}
          <div className="flex flex-wrap gap-2">
            {has('MEMBERSHIP_UPDATE') &&
            row.status !== 'CANCELLED' &&
            row.status !== 'FROZEN' &&
            editingId !== row.id ? (
              <Button
                variant="outline"
                onClick={() => {
                  setEditingId(row.id);
                  setEditStart(row.startDate);
                  setEditEnd(row.endDate);
                }}
              >
                Change dates
              </Button>
            ) : null}

            {has('MEMBERSHIP_FREEZE') && row.status === 'ACTIVE' ? (
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

            {has('MEMBERSHIP_FREEZE') && row.status === 'FROZEN' ? (
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
            (row.status === 'ACTIVE' || row.status === 'EXPIRED') ? (
              <Button
                variant="outline"
                type="button"
                className="border-orange-500 text-orange-500 hover:bg-orange-500 hover:text-white"
                onClick={() => openRenewal(row)}
              >
                Renew
              </Button>
            ) : null}

            {/* CANCEL */}
            {has('MEMBERSHIP_CANCEL') && row.status !== 'CANCELLED' ? (
              <Button
                variant="danger"
                type="button"
                onClick={() => {
                  const reason =
                    window.prompt('Cancel reason (optional)') ?? '';

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

            {/* DELETE */}
            {has('MEMBERSHIP_DELETE') &&
            (row.status === 'PENDING' || row.status === 'ACTIVE') &&
            row.paymentStatus === 'UNPAID' &&
            row.deviceSyncState === 'NOT_SYNCED' ? (
              <Button
                variant="danger"
                type="button"
                disabled={deleteMembership.isPending}
                onClick={() => {
                  const confirmed = window.confirm(
                    'Delete this membership? It will be removed from normal views but retained in membership history.',
                  );

                  if (confirmed) {
                    deleteMembership.mutate(row.id);
                  }
                }}
              >
                {deleteMembership.isPending ? 'Deleting...' : 'Delete'}
              </Button>
            ) : null}
          </div>
        </Card>
      ))}

      {/* RENEWAL MODAL */}
      {renewingId && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4">
          <Card className="w-full max-w-md overflow-hidden border border-[#29322d] bg-[#151a17] text-[#f1f5f2] shadow-2xl">
            {/* Header */}
            <div className="border-b border-[#29322d] bg-[#101512] px-6 py-5">
              <h2 className="text-lg font-semibold">Renew Membership</h2>

              <p className="mt-1 text-sm text-[#91a39a]">
                Select the plan for the next membership period.
              </p>
            </div>

            {/* Body */}
            <div className="space-y-5 px-6 py-5">
              {/* Current Plan */}
              {renewingMembership && (
                <div className="rounded-lg border border-[#29322d] bg-[#101512] p-4">
                  <div className="mb-2 text-xs font-medium uppercase tracking-wide text-[#91a39a]">
                    Current Membership
                  </div>

                  <div className="flex items-center justify-between">
                    <div>
                      <div className="font-medium text-[#f1f5f2]">
                        {renewingMembership.planName}
                      </div>

                      <div className="mt-1 text-sm text-[#91a39a]">
                        {formatDate(renewingMembership.startDate)} →{' '}
                        {formatDate(renewingMembership.endDate)}
                      </div>
                    </div>

                    <div className="font-semibold text-[#f1f5f2]">
                      {money(currentPlanPrice, renewingMembership.currency)}
                    </div>
                  </div>
                </div>
              )}

              {/* New Plan */}
              <div className="space-y-2">
                <Label
                  htmlFor="renew-plan"
                  className="text-sm font-medium text-[#d5ddd8]"
                >
                  New Plan
                </Label>

                <Select
                  id="renew-plan"
                  value={renewPlanId}
                  onChange={(e) => applyRenewPlan(e.target.value)}
                  className="border-[#29322d] bg-[#0d110f] text-[#f1f5f2]"
                >
                  <option value="">Select plan</option>

                  {activePlans.map((plan) => (
                    <option key={plan.id} value={plan.id}>
                      {plan.name} — {money(plan.price, plan.currency)}
                    </option>
                  ))}
                </Select>
              </div>

              {/* Dates */}
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label
                    htmlFor="renew-start"
                    className="text-sm text-[#d5ddd8]"
                  >
                    Start Date
                  </Label>

                  <Input
                    id="renew-start"
                    type="date"
                    value={renewStartDate}
                    onChange={(e) => applyRenewStart(e.target.value)}
                    className="border-[#29322d] bg-[#0d110f] text-[#f1f5f2]"
                  />
                </div>

                <div className="space-y-2">
                  <Label htmlFor="renew-end" className="text-sm text-[#d5ddd8]">
                    End Date
                  </Label>

                  <Input
                    id="renew-end"
                    type="date"
                    value={renewEndDate}
                    min={renewStartDate}
                    onChange={(e) => setRenewEndDate(e.target.value)}
                    className="border-[#29322d] bg-[#0d110f] text-[#f1f5f2]"
                  />
                </div>
              </div>

              {/* Payment Summary */}
              {selectedRenewPlan && renewingMembership ? (
                <div className="space-y-2 rounded-lg border border-[#29322d] bg-[#101512] p-4">
                  <p className="text-sm font-medium text-[#f1f5f2]">
                    Payment Summary
                  </p>

                  <div className="flex justify-between text-sm">
                    <span className="text-[#91a39a]">New Plan Price</span>

                    <span className="text-[#f1f5f2]">
                      {money(
                        selectedRenewPlan.price,
                        selectedRenewPlan.currency,
                      )}
                    </span>
                  </div>

                  {renewStartDate === renewingMembership.startDate ||
                  renewStartDate === addDays(renewingMembership.endDate, 1) ? (
                    <div className="flex justify-between text-sm">
                      <span className="text-[#91a39a]">
                        Current Plan Credit
                      </span>

                      <span className="text-[#f1f5f2]">
                        −{money(currentPlanPrice, renewingMembership.currency)}
                      </span>
                    </div>
                  ) : null}

                  <div className="flex justify-between border-t border-[#29322d] pt-2">
                    <span className="font-medium text-[#f1f5f2]">
                      Amount to Collect
                    </span>

                    <span className="font-semibold text-orange-400">
                      {money(additionalAmount, selectedRenewPlan.currency)}
                    </span>
                  </div>
                </div>
              ) : null}

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
            <div className="flex justify-end gap-3 border-t border-[#29322d] bg-[#101512] px-6 py-4">
              <Button
                variant="outline"
                type="button"
                className="border-[#29322d] bg-transparent text-[#d5ddd8] hover:bg-[#1d2520]"
                onClick={closeRenewal}
              >
                Cancel
              </Button>

              <Button
                type="button"
                disabled={
                  renew.isPending ||
                  !renewPlanId ||
                  !renewStartDate ||
                  !renewEndDate
                }
                className="bg-orange-500 text-white hover:bg-orange-600 disabled:bg-orange-500/40"
                onClick={() => renew.mutate()}
              >
                {renew.isPending ? 'Renewing...' : 'Renew Membership'}
              </Button>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
