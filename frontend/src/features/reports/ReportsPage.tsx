import { useMemo, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { QueryError } from "@/components/QueryError";
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Field,
  Input,
  PageHeader,
  Skeleton,
  StatCard,
  Table,
  TableShell,
  THead,
  Th,
  Td,
  Tr,
} from "@/components/ui";
import { api } from "@/lib/api";
import { formatDate, money } from "@/lib/cn";
import { statusTone } from "@/lib/status";

type Operations = {
  overview: {
    membersTotal: number;
    activeMembers: number;
    newMembers: number;
    activeMemberships: number;
    expiringIn7Days: number;
    expiringIn30Days: number;
    expiredMemberships: number;
    outstandingCount: number;
    outstandingAmount: number | string;
    collectionCount: number;
    collectionAmount: number | string;
    attendanceCount: number;
    uniqueAttendees: number;
    accessDenied: number;
    renewals: number;
  };
  collectionsByMethod: {
    method: string;
    count: number;
    amount: number | string;
  }[];
  payments: {
    id: string;
    memberName: string;
    memberCode: string;
    paidOn: string;
    amount: number | string;
    currency: string;
    method: string;
    status: string;
  }[];
  expiringIn7Days: {
    id: string;
    memberName: string;
    memberCode: string;
    planName: string;
    status: string;
    startDate: string;
    endDate: string;
    price: number | string;
    amountPaid: number | string;
    balance: number | string;
  }[];
  expiringMemberships: {
    id: string;
    memberName: string;
    memberCode: string;
    planName: string;
    status: string;
    startDate: string;
    endDate: string;
    price: number | string;
    amountPaid: number | string;
    balance: number | string;
  }[];
  outstandingDues: {
    id: string;
    memberName: string;
    memberCode: string;
    planName: string;
    status: string;
    endDate: string;
    planAmount: number | string;
    amountPaid: number | string;
    balance: number | string;
  }[];
  newMembers: {
    id: string;
    memberCode: string;
    name: string;
    phone?: string;
    joinedOn: string;
  }[];
  attendance: {
    memberId: string;
    memberCode: string;
    memberName: string;
    visits: number;
    lastVisit: string;
  }[];
  inactiveMembers: {
    id: string;
    memberCode: string;
    name: string;
    phone?: string;
    joinedOn: string;
  }[];
};

const iso = (date: Date) => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

const initialFrom = () => {
  const date = new Date();
  date.setDate(date.getDate() - 30);
  return iso(date);
};

const today = () => iso(new Date());

const methodLabel = (method: string) =>
    method
        .replaceAll("_", " ")
        .toLowerCase()
        .replace(/\b\w/g, (letter) => letter.toUpperCase());

export function ReportsPage() {
  const [from, setFrom] = useState(initialFrom);
  const [to, setTo] = useState(today);

  const query = useQuery({
    queryKey: ["reports", "operations", from, to],
    queryFn: () =>
        api<Operations>(`/api/v1/reports/operations?from=${from}&to=${to}`),
  });

  const periodLabel = useMemo(
      () => `${formatDate(from)} → ${formatDate(to)}`,
      [from, to]
  );

  return (
      <div className="space-y-8">
        <PageHeader
            title="Reports"
            description="Day-to-day gym operations: collections, dues, memberships, attendance and member activity."
            actions={
              <div className="flex flex-wrap gap-2">
                <Link to="/app/reports/memberships">
                  <Button variant="outline" size="sm">
                    Membership snapshot
                  </Button>
                </Link>
                <Link to="/app/reports/devices">
                  <Button variant="outline" size="sm">
                    Device health
                  </Button>
                </Link>
              </div>
            }
        />

        <Card>
          <div className="grid gap-4 sm:grid-cols-3 sm:items-end">
            <Field label="From">
              <Input
                  type="date"
                  value={from}
                  max={to}
                  onChange={(e) => setFrom(e.target.value)}
              />
            </Field>
            <Field label="To">
              <Input
                  type="date"
                  value={to}
                  min={from}
                  max={today()}
                  onChange={(e) => setTo(e.target.value)}
              />
            </Field>
            <div className="flex gap-2">
              <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    setFrom(initialFrom());
                    setTo(today());
                  }}
              >
                Last 30 days
              </Button>
              <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    const start = new Date();
                    start.setDate(1);
                    setFrom(iso(start));
                    setTo(today());
                  }}
              >
                This month
              </Button>
            </div>
          </div>
        </Card>

        {query.isLoading ? (
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              {Array.from({ length: 8 }).map((_, i) => (
                  <Skeleton key={i} className="h-28" />
              ))}
            </div>
        ) : null}
        {query.error ? (
            <QueryError error={query.error} onRetry={() => void query.refetch()} />
        ) : null}

        {query.data ? (
            <>
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                <StatCard
                    label="Members"
                    value={query.data.overview.membersTotal}
                    hint={`${query.data.overview.activeMembers} active`}
                />
                <StatCard
                    label="New members"
                    value={query.data.overview.newMembers}
                    hint={periodLabel}
                />
                <StatCard
                    label="Active memberships"
                    value={query.data.overview.activeMemberships}
                    hint={`${query.data.overview.renewals} started in period`}
                />
                <StatCard
                    label="Collections"
                    value={money(query.data.overview.collectionAmount, "INR")}
                    hint={`${query.data.overview.collectionCount} completed payments`}
                />
                <StatCard
                    label="Outstanding dues"
                    value={money(query.data.overview.outstandingAmount, "INR")}
                    hint={`${query.data.overview.outstandingCount} memberships with balance`}
                />
                <StatCard
                    label="Expiring soon"
                    value={query.data.overview.expiringIn7Days}
                    hint={`${query.data.overview.expiringIn30Days} within 30 days`}
                />
                <StatCard
                    label="Attendance"
                    value={query.data.overview.attendanceCount}
                    hint={`${query.data.overview.uniqueAttendees} unique members`}
                />
                <StatCard
                    label="Access denied"
                    value={query.data.overview.accessDenied}
                    hint={`${query.data.overview.expiredMemberships} expired memberships`}
                />
              </div>

              <ReportSection
                  title="Collections"
                  description={`Completed payments for ${periodLabel}`}
                  count={query.data.overview.collectionCount}
              >
                <div className="mb-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
                  {query.data.collectionsByMethod.map((row) => (
                      <div
                          key={row.method}
                          className="rounded-xl border border-line bg-raised/40 p-4"
                      >
                        <div className="text-xs text-muted">
                          {methodLabel(row.method)}
                        </div>
                        <div className="mt-1 text-lg font-bold">
                          {money(row.amount, "INR")}
                        </div>
                        <div className="mt-1 text-xs text-muted">
                          {row.count} payments
                        </div>
                      </div>
                  ))}
                </div>
                {query.data.payments.length === 0 ? (
                    <EmptyState
                        title="No collections"
                        body={`No payments were recorded for ${periodLabel}.`}
                    />
                ) : (
                    <TableShell>
                      <Table>
                        <THead>
                          <tr>
                            <Th>Paid on</Th>
                            <Th>Member</Th>
                            <Th>Member ID</Th>
                            <Th>Amount</Th>
                            <Th>Method</Th>
                            <Th>Status</Th>
                          </tr>
                        </THead>
                        <tbody>
                        {query.data.payments.map((p) => (
                            <Tr key={p.id}>
                              <Td>{formatDate(p.paidOn)}</Td>
                              <Td className="font-medium">{p.memberName}</Td>
                              <Td className="text-muted">{p.memberCode || "—"}</Td>
                              <Td className="font-medium tabular-nums">
                                {money(p.amount, p.currency)}
                              </Td>
                              <Td>{methodLabel(p.method)}</Td>
                              <Td>
                                <Badge tone={statusTone(p.status)}>{p.status}</Badge>
                              </Td>
                            </Tr>
                        ))}
                        </tbody>
                      </Table>
                    </TableShell>
                )}
              </ReportSection>

              <ReportSection
                  title="Outstanding dues"
                  description="Membership balances still to be collected."
                  count={query.data.overview.outstandingCount}
                  amount={money(query.data.overview.outstandingAmount, "INR")}
              >
                {query.data.outstandingDues.length === 0 ? (
                    <EmptyState
                        title="No outstanding dues"
                        body="All non-cancelled memberships are fully paid."
                    />
                ) : (
                    <TableShell>
                      <Table>
                        <THead>
                          <tr>
                            <Th>Member</Th>
                            <Th>Member ID</Th>
                            <Th>Plan</Th>
                            <Th>Status</Th>
                            <Th>End date</Th>
                            <Th>Plan amount</Th>
                            <Th>Paid</Th>
                            <Th>Due</Th>
                          </tr>
                        </THead>
                        <tbody>
                        {query.data.outstandingDues.map((r) => (
                            <Tr key={r.id}>
                              <Td className="font-medium">{r.memberName}</Td>
                              <Td className="text-muted">{r.memberCode || "—"}</Td>
                              <Td>{r.planName}</Td>
                              <Td>
                                <Badge tone={statusTone(r.status)}>{r.status}</Badge>
                              </Td>
                              <Td>{formatDate(r.endDate)}</Td>
                              <Td className="tabular-nums">
                                {money(r.planAmount, "INR")}
                              </Td>
                              <Td className="tabular-nums">
                                {money(r.amountPaid, "INR")}
                              </Td>
                              <Td className="font-semibold tabular-nums">
                                {money(r.balance, "INR")}
                              </Td>
                            </Tr>
                        ))}
                        </tbody>
                      </Table>
                    </TableShell>
                )}
              </ReportSection>

              <ReportSection
                  title="Memberships expiring in next 7 days"
                  description="Active memberships that will expire today or within the next 7 days. These members should be contacted for renewal."
                  count={query.data.overview.expiringIn7Days}
              >
                {query.data.expiringIn7Days.length === 0 ? (
                    <EmptyState
                        title="No memberships expiring in the next 7 days"
                        body="There are no active memberships ending today or within the next 7 days."
                    />
                ) : (
                    <TableShell>
                      <Table>
                        <THead>
                          <tr>
                            <Th>Member</Th>
                            <Th>Member ID</Th>
                            <Th>Plan</Th>
                            <Th>End date</Th>
                            <Th>Paid</Th>
                            <Th>Balance</Th>
                          </tr>
                        </THead>
                        <tbody>
                        {query.data.expiringIn7Days.map((r) => (
                            <Tr key={r.id}>
                              <Td className="font-medium">{r.memberName}</Td>
                              <Td className="text-muted">{r.memberCode || "—"}</Td>
                              <Td>{r.planName}</Td>
                              <Td className="font-semibold">
                                {formatDate(r.endDate)}
                              </Td>
                              <Td className="tabular-nums">
                                {money(r.amountPaid, "INR")}
                              </Td>
                              <Td className="font-semibold tabular-nums">
                                {money(r.balance, "INR")}
                              </Td>
                            </Tr>
                        ))}
                        </tbody>
                      </Table>
                    </TableShell>
                )}
              </ReportSection>

              <ReportSection
                  title="Memberships expiring"
                  description="Active memberships expiring within the next 30 days."
                  count={query.data.overview.expiringIn30Days}
              >
                {query.data.expiringMemberships.length === 0 ? (
                    <EmptyState
                        title="Nothing expiring soon"
                        body="No active memberships end within the next 30 days."
                    />
                ) : (
                    <TableShell>
                      <Table>
                        <THead>
                          <tr>
                            <Th>Member</Th>
                            <Th>Member ID</Th>
                            <Th>Plan</Th>
                            <Th>End date</Th>
                            <Th>Paid</Th>
                            <Th>Balance</Th>
                          </tr>
                        </THead>
                        <tbody>
                        {query.data.expiringMemberships.map((r) => (
                            <Tr key={r.id}>
                              <Td className="font-medium">{r.memberName}</Td>
                              <Td className="text-muted">{r.memberCode || "—"}</Td>
                              <Td>{r.planName}</Td>
                              <Td
                                  className={
                                    r.endDate <= today()
                                        ? "font-semibold text-danger"
                                        : ""
                                  }
                              >
                                {formatDate(r.endDate)}
                              </Td>
                              <Td className="tabular-nums">
                                {money(r.amountPaid, "INR")}
                              </Td>
                              <Td className="tabular-nums">
                                {money(r.balance, "INR")}
                              </Td>
                            </Tr>
                        ))}
                        </tbody>
                      </Table>
                    </TableShell>
                )}
              </ReportSection>

              <div className="grid gap-8 xl:grid-cols-2">
                <ReportSection
                    title="New members"
                    description={`Members who joined during ${periodLabel}.`}
                    count={query.data.overview.newMembers}
                >
                  {query.data.newMembers.length === 0 ? (
                      <EmptyState
                          title="No new members"
                          body="No members joined during the selected period."
                      />
                  ) : (
                      <TableShell>
                        <Table>
                          <THead>
                            <tr>
                              <Th>Joined</Th>
                              <Th>Member</Th>
                              <Th>Member ID</Th>
                              <Th>Phone</Th>
                            </tr>
                          </THead>
                          <tbody>
                          {query.data.newMembers.map((m) => (
                              <Tr key={m.id}>
                                <Td>{formatDate(m.joinedOn)}</Td>
                                <Td className="font-medium">{m.name}</Td>
                                <Td className="text-muted">{m.memberCode}</Td>
                                <Td>{m.phone || "—"}</Td>
                              </Tr>
                          ))}
                          </tbody>
                        </Table>
                      </TableShell>
                  )}
                </ReportSection>

                <ReportSection
                    title="Attendance"
                    description={`Granted visits during ${periodLabel}.`}
                    count={query.data.overview.attendanceCount}
                    amount={`${query.data.overview.uniqueAttendees} unique members`}
                >
                  {query.data.attendance.length === 0 ? (
                      <EmptyState
                          title="No attendance"
                          body="No granted attendance events were recorded during the selected period."
                      />
                  ) : (
                      <TableShell>
                        <Table>
                          <THead>
                            <tr>
                              <Th>Member</Th>
                              <Th>Member ID</Th>
                              <Th>Visits</Th>
                              <Th>Last visit</Th>
                            </tr>
                          </THead>
                          <tbody>
                          {query.data.attendance.map((r) => (
                              <Tr key={r.memberId}>
                                <Td className="font-medium">{r.memberName}</Td>
                                <Td className="text-muted">{r.memberCode || "—"}</Td>
                                <Td className="font-semibold tabular-nums">
                                  {r.visits}
                                </Td>
                                <Td>
                                  {r.lastVisit
                                      ? new Date(r.lastVisit).toLocaleString()
                                      : "—"}
                                </Td>
                              </Tr>
                          ))}
                          </tbody>
                        </Table>
                      </TableShell>
                  )}
                </ReportSection>
              </div>

              <ReportSection
                  title="Active members with no visit"
                  description={`Active members with no granted attendance during ${periodLabel}.`}
                  count={query.data.inactiveMembers.length}
              >
                {query.data.inactiveMembers.length === 0 ? (
                    <EmptyState
                        title="Everyone has visited"
                        body="Every active member has at least one granted attendance event in the selected period."
                    />
                ) : (
                    <TableShell>
                      <Table>
                        <THead>
                          <tr>
                            <Th>Member</Th>
                            <Th>Member ID</Th>
                            <Th>Phone</Th>
                            <Th>Joined</Th>
                          </tr>
                        </THead>
                        <tbody>
                        {query.data.inactiveMembers.map((m) => (
                            <Tr key={m.id}>
                              <Td className="font-medium">{m.name}</Td>
                              <Td className="text-muted">{m.memberCode}</Td>
                              <Td>{m.phone || "—"}</Td>
                              <Td>{formatDate(m.joinedOn)}</Td>
                            </Tr>
                        ))}
                        </tbody>
                      </Table>
                    </TableShell>
                )}
              </ReportSection>
            </>
        ) : null}
      </div>
  );
}

function ReportSection({
                         title,
                         description,
                         count,
                         amount,
                         children,
                       }: {
  title: string;
  description: string;
  count: number;
  amount?: string;
  children: ReactNode;
}) {
  return (
      <section>
        <div className="mb-3 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="text-sm font-semibold tracking-tight">{title}</h2>
            <p className="mt-0.5 text-xs text-muted">{description}</p>
          </div>
          <div className="flex items-center gap-2">
            <Badge tone="accent">{count} records</Badge>
            {amount ? <span className="text-xs text-muted">{amount}</span> : null}
          </div>
        </div>
        {children}
      </section>
  );
}

export function MembershipReportPage() {
  const rows = useQuery({
    queryKey: ["reports", "memberships"],
    queryFn: () =>
        api<Record<string, unknown>[]>("/api/v1/reports/memberships"),
  });
  return (
      <div>
        <PageHeader
            title="Membership report"
            description="Current membership rows from the reporting API."
        />
        {rows.isLoading ? <Skeleton className="h-40" /> : null}
        {rows.error ? (
            <QueryError error={rows.error} onRetry={() => void rows.refetch()} />
        ) : null}
        {rows.data && rows.data.length === 0 ? (
            <EmptyState
                title="No memberships"
                body="Memberships appear here once members are enrolled on a plan."
            />
        ) : null}
        {rows.data && rows.data.length > 0 ? (
            <TableShell>
              <Table>
                <THead>
                  <tr>
                    <Th>Plan</Th>
                    <Th>Status</Th>
                    <Th>Dates</Th>
                    <Th>Paid</Th>
                  </tr>
                </THead>
                <tbody>
                {rows.data.map((row) => (
                    <Tr key={String(row.id)}>
                      <Td className="font-medium">{String(row.planName)}</Td>
                      <Td>
                        <Badge tone={statusTone(String(row.status))}>
                          {String(row.status)}
                        </Badge>
                      </Td>
                      <Td className="text-muted">
                        {String(row.startDate)} → {String(row.endDate)}
                      </Td>
                      <Td className="tabular-nums">{String(row.amountPaid)}</Td>
                    </Tr>
                ))}
                </tbody>
              </Table>
            </TableShell>
        ) : null}
      </div>
  );
}

export function DeviceReportPage() {
  const summary = useQuery({
    queryKey: ["reports", "summary"],
    queryFn: () =>
        api<{ devices: { id: string; name: string; connectionState: string }[] }>(
            "/api/v1/reports/summary"
        ),
  });
  return (
      <div>
        <PageHeader
            title="Device report"
            description="Connection state at query time — not a live status lamp."
        />
        {summary.isLoading ? <Skeleton className="h-32" /> : null}
        {summary.error ? (
            <QueryError
                error={summary.error}
                onRetry={() => void summary.refetch()}
            />
        ) : null}
        {summary.data?.devices?.length === 0 ? (
            <EmptyState
                title="No devices"
                body="Register a gateway and TrueFace terminal to see health here."
            />
        ) : null}
        {summary.data?.devices?.length ? (
            <div className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-panel shadow-[var(--shadow-panel)]">
              {summary.data.devices.map((d) => (
                  <Link
                      key={d.id}
                      to={`/app/devices/${d.id}`}
                      className="flex items-center justify-between gap-3 px-4 py-3.5 text-sm transition hover:bg-raised/50"
                  >
                    <span className="font-medium">{d.name}</span>
                    <Badge tone={statusTone(d.connectionState)}>
                      {d.connectionState}
                    </Badge>
                  </Link>
              ))}
            </div>
        ) : null}
      </div>
  );
}
