# Sync approach plan

Plan only — implement when asked. Updated **29 Oct 2026** from the 27 Oct draft plus live logs/DB through ~29 Oct midday IST.

## Goals

1. Stop unnecessary all-day full-roster work when the gym rarely edits devices directly.
2. Keep **UI → device** writes fast and reliable (not stuck behind list fences).
3. Keep a clear escape hatch when someone *does* change a device.
4. Give the member UI a light way to show live device state without a 1,200-person scan.
5. Fix create + quick UI update opening a false Review CONFLICT.
6. Stop secondary log/ops pain (staff-live spam, heartbeat races, attendance deadlock, dedupe noise) without fighting the sync model.

## What we measured / confirmed

| Finding | Evidence / meaning |
|--------|---------------------|
| Hourly full lists are the main drag | 65 list saves in ~38h; ~1242 people; p50 ~4.5 min, avg ~5.2 min, max ~9.8 min; each fences member sync. |
| Lists pause member sync | While a list saves, that reader does not apply desired → UI edits wait; one reader can finish while the other lags. |
| “Offline” often ≠ gym LAN down | Short WS drops + long list work + heartbeat lag; process restarts cause long gaps. |
| Desired lag is real under that load | Entry/Exit lag_revs=5 (29 Oct); Sameer 1242 / Monika 6 / Sushila 7 stuck on soft GetUser. |
| Photos: hashes exist but work still repeats | Checksum/cache misses, cold cache after restart, enrollment `want-photos`; overnight cost was mostly list/fence, not mass new JPEGs. |
| Create then update is **one row**, latest wins | Desired is one projection per member/reader; membership/access **bumps the same row**. Gateway may never see a separate “create” revision. |
| Exit 1235 CONFLICT (27 Oct) | Create → quick membership update → later apply with wrong **expect present** on empty reader → CONFLICT `reader_absent`. Accept server fixed it. |
| Soft “6 attempts / could not read user” | Soft retries when GetUser is Unreadable / not confirmed ABSENT under load. Some later ACK; some stay stuck (1242) while fenced or contended. |
| Staff-live invalid token storm | ~1244 of ~1444 WARNs in 38h: `Rejecting staff live handshake: invalid token` on reconnect loop. |
| Gateway WS flaps | ~57 closes (1006 / reset / broken pipe) amplify heartbeat races and message retries. |
| Heartbeat optimistic lock | 15× Gateway `ObjectOptimisticLockingFailureException` → ERROR on HEARTBEAT. |
| Attendance deadlock | 1× MySQL 1213 on `attendance_event` insert mid-list → unhandled 500. |
| messageId dedupe 1062 | 33× duplicate `gateway_message_dedupe` — should be idempotent, logged as failure today. |
| Stale observation flood | ~1496 ignored after a later list; correct, but noisy and wasteful. |
| Future-dated punches | Punch after server time stored; cursor not advanced by them. |

Ops agreement: device-side create/delete/edit is rare. Prefer **Read this reader** when it happens.

---

## Target operating model

```text
Default day-to-day
  • No automatic periodic full roster upload
  • Desired path (UI → device) stays hot; never blocked by idle lists
  • Member page may peek one user on Entry/Exit when opened (display only)

When someone changes a device
  • Staff press Read this reader (full list + photos as today for that read)

Reconnect / safety (v1)
  • No automatic full roster on reconnect
  • Rely on desired journal + explicit Read this reader
  • If a light reconnect check is added later, it must use the unchanged-fingerprint short-circuit (B) and must not fence desired
```

---

## Final decisions (locked)

Each item has one call. Later sections implement these; do not reopen without a new evidence pass.

| ID | Topic | Decision |
|----|--------|----------|
| **A** | Periodic full roster | **Default OFF.** Production does not poll a full roster on a timer. Staff **Read this reader** (and explicit import/bootstrap) remain the only full trusted lists. No reconnect-triggered full list in v1. |
| **B** | Unchanged list | **Always short-circuit.** Any list path that still runs (staff Read, bootstrap, future light check): if roster fingerprint matches last saved → ACK fast, **do not** hold the member-sync fence for a full re-admit. |
| **C** | Create → quick update | **Keep create semantics until first ACK.** While never successfully applied on that reader, precondition stays **ABSENT** (create) across membership/name/access bumps of the same seed. Do **not** invent “expect present” from baseline/obs for an id never ACK’d present for this member. One coalesced snapshot before first pull is fine. |
| **C′** | False CONFLICT UX | **Retry create, do not open Review** for “brand-new mapping, expect person, reader empty / never ACK’d present.” Staff Review stays for real device↔server disagreements. |
| **D** | Member peek | **Yes, after A/C.** On member open (debounced 30–60s): GetUser (+ face if needed) on mapped Entry/Exit ids. **Display only** — must **not** upsert observation/baseline that would flip expected for an unacked first presence (protects C). Priority: desired apply > peek. Offline → last synced + clear offline state. |
| **E** | Photos / hashes | **Keep current desired face skip; harden cache.** Prefer reader checksum + durable cache across gateway restarts; server must not `want-photos` for hashes it already has. |
| **F** | Soft “6 attempts” | **Fence/busy must not burn attempts into permanent-looking failure.** Under list fence or reader Unreadable under load: backoff GetUser; do not increment `apply_attempts` toward scary/permanent banners. Unreadable ≠ confirmed ABSENT. Permanent only when the reader returns a clear, repeatable refusal after load is gone. UI: **still retrying** vs **permanent**. |
| **G** | Ops copy | **Ship with A.** Device edits → Read this reader. “Saving reader list…” ≠ “Gym offline” when WS is up. |
| **H** | Staff-live WS | **Fix client token + server backoff.** Refresh/rotate before reconnect; after invalid token, exponential backoff (no ~16s infinite retry). Do not weaken auth. |
| **I** | Gateway heartbeat `@Version` | **Must not ERROR the message path.** Catch/retry optimistic failure or stop versioning pure heartbeat fields so concurrent HEARTBEATs cannot fail the handler. |
| **J** | Attendance deadlock | **Retry transient 1213** inside ingestion (bounded retries). Never surface as unhandled 500 if a retry would succeed. |
| **K** | messageId 1062 | **Treat duplicate dedupe key as success** to the gateway (already processed). Do not re-run side effects. Log at debug/trace, not WARN/ERROR. |
| **L** | Stale observations | **After a newer list is authoritative, stop per-obs INFO spam.** Prefer one summary log; cancel/ignore superseded PersonInfo work where cheap. Behavior (ignore stale) stays. |
| **M** | Punch clock skew | **Clamp to server time and advance cursor** for small skew; do not leave cursor stuck on “future” punches that were stored. Large skew: still store + clamp + log WARN (investigate device clock). |
| **N** | WS flaps (1006/reset) | **No separate project in v1.** Re-check after A + I + H. Only then consider proxy idle timeouts / gateway keepalives if flaps remain high. |

### Allocator / already shipped (unchanged)

- Device user id allocate = **max(taken)+1** only (first-gap rejected).
- Stale observation **prune** when roster/deleted confirms id gone — shipped 27 Oct 2026.
- Desired remains a **full user record** per revision (not TrueFace partial field patches); face stays separate when hash unchanged.

---

## Conflict check (why this set is safe together)

| Risk | How we avoid it |
|------|------------------|
| A off → miss rare device-side edits | Explicit **Read this reader** + staff note (G). No silent “trust device forever” claim. |
| B short-circuit hides a real device edit | Fingerprint must include ids + critical fields + face hashes already on the wire. Change on device → fingerprint changes → full save. |
| D peek breaks C (obs says present before create ACK) | **Hard rule:** peek does not write observation/baseline that affects `expected()` for never-ACK’d rows. Display-only. |
| F “don’t count attempts” hides real failures | Only suppress attempt burn under fence / Unreadable-busy. Clear refusal after quiet reader still goes permanent. |
| C′ auto-retry vs real CONFLICT | Limited to never-ACK’d first presence / brand-new mapping. Established members still open Review. |
| K dedupe-as-success double-applies or drops work | Duplicate key means prior insert won; return success **without** re-applying. Processing must stay tied to dedupe so “insert succeeded, handler died” is already handled by existing design — do not invent a second process path. |
| I heartbeat change loses status accuracy | Still update last_heartbeat; only remove fail-on-version-collision. Status events can stay coalesced. |
| J retries + list fence | Attendance retry is short and local to the insert txn; does not extend roster fence. |
| H backoff + live UI “dead” | Backoff only after **invalid token**; successful auth reconnects normally. Refresh path is the real fix. |
| E photo cache + A | Less automatic list ⇒ less photo churn; E still needed on staff Read / enroll. |
| Doing D before A | Rejected in order below — peek would compete with fences and muddy Unreadable soft-fails. |

---

## Workstreams

### A. Stop periodic full roster (highest leverage)

- [ ] Gate periodic full roster behind config; **production default off** (or `RosterPollSeconds` effectively disabled).
- [ ] Keep **Read this reader** and import/bootstrap as the full trusted list path.
- [ ] No reconnect full list in v1.
- [ ] Document for gym staff (ties to G).

**Success:** Normal day has no multi-minute fence from idle polls; UI creates/edits apply without waiting on a 1,200-person save.

### B. Never let an unchanged list block UI→device

- [ ] Fingerprint / hash of roster (ids + critical fields + face hashes).
- [ ] If identical to last saved → ACK quickly; **do not** hold fence for full re-admit (or release fence immediately).
- [ ] Desired/WS notify stays independent of roster.

**Success:** Staff Read (or any future auto check) of an unchanged reader cannot stall member writes for minutes.

### C. Fix create → quick update precondition

- [ ] Unacked first presence → keep expected **ABSENT** across enriching publishes.
- [ ] Never derive expect-present from baseline/obs for never-ACK’d mapping.
- [ ] Optional coalesce of create+membership into one snapshot before first pull.
- [ ] **C′:** brand-new never-ACK’d empty reader → retry create, not Review CONFLICT.

**Success:** Create + add plan within seconds, gateway busy, does **not** open Entry/Exit CONFLICT `reader_absent` for that new member.

### D. On-demand per-member peek

- [ ] Member open → debounced GetUser on Entry/Exit mapped ids.
- [ ] Display only; **no expected-affecting obs write** for unacked first presence.
- [ ] Offline → last synced + offline state; show open Review when present.
- [ ] Priority: desired apply > peek.

**Success:** Opening a member shows live vs last-known without a 1,200-person list and without reintroducing 1235-class expected bugs.

### E. Photos / hashes

- [ ] Durable checksum cache across gateway restarts where safe.
- [ ] No `want-photos` for hashes already stored for that enrollment.
- [ ] Keep desired face skip on same hash.

### F. Soft apply failures

- [ ] Under fence / Unreadable-busy: backoff; **do not** burn `apply_attempts` toward permanent.
- [ ] UI: still retrying vs permanent.
- [ ] After A, re-check stuck rows (e.g. 1242); repair only if still wrong once load is gone.

### G. Ops / messaging

- [ ] Owner note: do not edit users on the device; if you must, **Read this reader**.
- [ ] Status copy: list save ≠ gym offline when WS is up.

### H. Staff-live invalid token storm

- [ ] Client: refresh JWT before staff-live reconnect; stop reconnect loop on auth reject until new token.
- [ ] Server: after invalid token, do not invite tight retry (optional rate-limit log).

**Success:** Invalid-token WARNs drop from hundreds/day to rare (expired session / logout).

### I. Gateway heartbeat optimistic lock

- [ ] HEARTBEAT handler never fails the message solely due to `@Version` collision.
- [ ] Prefer retry-once or non-versioned heartbeat timestamp update.

**Success:** No ERROR lines for routine HEARTBEAT under reconnect.

### J. Attendance deadlock retry

- [ ] Bounded retry on MySQL 1213 for `attendance_event` insert path.
- [ ] Gateway receives success or a clean retryable error, not an unhandled 500.

### K. messageId dedupe idempotency

- [ ] Duplicate `gateway_message_dedupe` PRIMARY → treat as already processed; ACK success; no side-effect replay.
- [ ] Downgrade log level.

### L. Stale observation noise

- [ ] One summary (or debug) instead of per-observation INFO when scan superseded.
- [ ] Optionally stop scheduling PersonInfo for superseded scans.

### M. Punch clock skew

- [ ] Clamp occurred_at to server now (or small skew window) and **advance cursor** so future-dated punches do not stall pull.

### N. WS flaps (observe only)

- [ ] After A+I+H, count 1006/reset again; only then change proxy/keepalive if still elevated.

---

## Implementation order (do in this sequence)

Order is dependency-safe: each step either removes load, fixes correctness that later steps rely on, or is isolated plumbing that cannot reopen C/D.

### Phase 1 — Stop load + fix write correctness

1. **A** — Turn off periodic full roster (config + verify Read this reader still works).  
2. **G** — Staff/ops copy with A (same change set is fine).  
3. **C + C′** — Create→update expected/ABSENT + no false Review for never-ACK’d empty.  
4. **F** — Soft-fail attempt rules under fence/Unreadable (aligns with C; does not change expected).

### Phase 2 — Make remaining list + gateway traffic safe

5. **B** — Unchanged-list short-circuit (protects staff Read and any future light check).  
6. **J** — Attendance deadlock retry (isolated).  
7. **K** — Dedupe 1062 = success (isolated).  
8. **I** — Heartbeat optimistic-lock handling (isolated; helps WS reconnect storms).

### Phase 3 — UX and noise (only after Phase 1)

9. **H** — Staff-live token refresh + backoff.  
10. **D** — Per-member peek with display-only / no obs pollution rule.  
11. **E** — Photo/hash cache hardening.  
12. **L** — Stale-obs log/work reduction.  
13. **M** — Punch skew clamp + cursor advance.  
14. **N** — Re-measure WS flaps; act only if still bad.

Do **not** ship D before A and C. Do **not** add reconnect full roster without B. Do **not** weaken K into “reprocess on duplicate.”

---

## Out of scope

- Prefer-member-id / first-gap allocator (rejected; keep max+1).  
- Remapping tooling beyond existing one-off repair.  
- TrueFace partial-field patches.  
- Redesigning Review for established present/absent mismatches (except C′ for never-ACK’d creates).  
- Proactive WS proxy redesign before N’s re-measure.

## References

- Allocator / stale observation prune: shipped 27 Oct 2026.  
- Incident: Exit device user 1235, member Naman Maan; CONFLICT `reader_absent`; Accept server ~19:49 IST 27 Oct.  
- Follow-up window: backend logs ~27 Oct evening → 29 Oct midday; stuck soft-fails 1242/6/7; staff-live ~1244 invalid tokens; 15 gateway heartbeat opt-locks; attendance deadlock; 33 dedupe 1062.  
- Related: `docs/sync-performance-backlog.md`, `docs/architecture/device-sync-architecture.md`, `docs/architecture/open-questions.md` (RosterPollSeconds product rule superseded for this site by **A: default OFF** given multi-minute saves).
