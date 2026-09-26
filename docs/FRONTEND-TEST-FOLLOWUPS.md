# Frontend test follow-ups

Items deferred or remaining after the batch that covered Kunal observations **1–3 and 5–10**.

## Closed / product decisions (this batch)

| # | Decision |
|---|----------|
| 1 | Forgot password: UI directs users to contact a **Super Admin** only (no email flow). |
| 9 | **No** inactivity logout. Session ends on explicit sign-out or refresh-cookie expiry (~30 days). |

## Deferred

### 4 — Photo upload / camera with head–shoulder frame

Staff member create does not capture a photograph. Face enrolment remains on-device (TrueFace).  
**Ambiguity:** Profile photo only vs face enroll; storage/retention if any image is held.

## Remaining (not in this batch)

### 11 — Membership on inactive member; status stays INACTIVE

Membership create does not check member account status and does not auto-activate the member. Door access still denies `INACTIVE`.  
**Ambiguity:** Block create vs require Reactivate (#7, now shipped) first vs auto-activate.

### 12 — Payments / Reports empty → 500

Empty list/summary paths are intended to return **200**. Need exact failing URL, role, date filters, and response body from the meeting to treat as a bug.

### 13 — Pending payments with member details

Best current view: **Reports → outstanding dues**. Global Payments table is recorded payments and omits member. No dedicated pending-payments screen.  
**Ambiguity:** Pending = balance > 0 and/or `UNPAID`/`PARTIAL`? Under Payments nav?

### 14 — Upgrade monthly → quarterly / change plan

No mid-cycle change-plan API. Date edit ≠ plan change. Delete+recreate is messy with payment history.  
**Ambiguity:** Proration/credit; amendment vs new membership row; device sync.

### 15 — Member detail payments: date and mode

API returns `paidOn` and `method`; member detail UI omits date (global Payments table already shows it).

### 16 — Device enrolment section unclear

Gateway enroll token vs member→device face map confuse testers. Needs clearer copy, stepper, or hide until devices exist.

### 17 — Member IDs like `MBR-SHVM` from name

Today: `MBR-` + random 6 chars.  
**Ambiguity:** Algorithm, collisions, immutability after create.

### 18 — Audit: member id column

UI shows resource type+id; `details` often has `memberId` but is unused.  
**Ambiguity:** UUID vs `memberCode`; parse `details` vs denormalize on write.

### 19 — Enquiries / Notifications / Reports / Payments QA

Surfaces exist; automated FE e2e is login-only. Deeper functional testing still required after the above fixes.
