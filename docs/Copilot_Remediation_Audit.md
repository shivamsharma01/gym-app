# Copilot remediation audit (Prompt 1 — evidence-based)

**Date:** 17 October 2026  
**Branch:** `feat/gateway_after_fixes` @ `023a57b`  
**Sources:** current code, `docs/architecture/*`, gateway log from 15 Oct 2026, prior production DB counts (15 Oct session). Draft Copilot prompt reports were used as leads and then discarded; this audit and `docs/Copilot_Remediation_Progress.md` are the kept record.

This audit verified the Copilot reports against the tree. Product changes from the remediation sequence are tracked in `docs/Copilot_Remediation_Progress.md`.

---

## 1. Repository baseline

| Item | Value |
| --- | --- |
| Branch / HEAD | `feat/gateway_after_fixes` / `023a57b` |
| Docs used | `docs/architecture/execution-plan.md`, `device-sync-architecture.md`, `sync-performance-backlog.md`, `device-sdk.md`, `open-questions.md` |
| Log artifact | `gateway-20261015.log` (~31 MB, 15 Oct) |
| Already in tree (post–15 Oct) | Wanted-face upload, checksum paging stop-at-total, day-based validity read-back, ADMIN carry-across-readers, `closeLeft` for gone undecided people, roster observe fast-path, attendance lock fix, batch attendance upload |

---

## 2. Claim-by-claim verdict table

| Claim (from Prompt 1/2 reports) | Verdict | Evidence | Confidence |
| --- | --- | --- | --- |
| Import created ~990 of ~1154–1217 because name/face “duplicates” are skipped | **Verified** (counts ~992 / 1217; skip = pending, not lost) | `ReaderImportStep.createUnlessKnown` → `MemberMatcher.existing`; prod: 63 no-photo + 162 same-name pending | High |
| Bootstrap report is required to import the skipped ones | **Partially verified** | Review create/link also works; bootstrap classifies and can seed; import does not auto-create name matches | High |
| Photos re-uploaded every scan | **Contradicted** (steady state) | Wanted list empty when unconsumed uploads exist; log 21:17 no upload; 21:42 uploaded 160 only when wanted | High |
| Cold restart re-downloads photos from reader | **Verified** | In-memory `_memory.Faces`; log 19:38 downloaded 1154 in 402 s after install | High |
| Checksum list failed at index 1154 | **Verified** (old); **fixed** in current code | Log 18:47–19:33; after 19:38 `1154 photo checksum(s)` | High |
| Reader 2 `read-back mismatch` = SameHash only | **Partially verified** | Dates were a proven cause (`EndOfDay` + strict time compare); day compare is in tree. Photo SHA may still fail if device re-encodes | Medium |
| Presence-only photo verify is the fix | **Contradicted by architecture + Remediation Prompt 3** | Execution plan: verify before ack; Prompt 3 forbids non-empty-only success | High |
| Independent reader workers already exist (V7) | **Verified** | `GatewayWorker` / per-reader `DesiredRevisionPath`; do not add competing parallelism | High |
| Parallelize writes on one reader | **Contradicted** | V16 one writer per reader; `LockedReaderAdapter` | High |
| Fence 120 s → full rescan | **Verified** | `DesiredPathOptions.FenceTimeout` default 2 min; `ObservationProcessedAsync` → `Unobserved` | High |
| HTTP timeout 20 s | **Verified** | `BackendLink` / `DesiredStateClient` | High |
| Import locks readers once per row | **Verified** | `ReaderImportStep` `@Transactional` + `lockReaders` per person | High |
| `force=true` on import | **Rejected** (see conflicts) | Conflicts V13 / slice-3 intentional hold; Remediation Prompt 2 forbids blanket force | High |
| Exact same `deviceUserId` on both readers | **Contradicted by plan** | Allocator is per-reader H+1; mapping is per reader | High |
| Validity forced to 00:00–23:59:59 | **Verified intentional (V3)** | `DesiredProjectionService` + `ReaderLocalClock.EndOfDay` | High |
| Name collapsed to `fullName` then re-sliced | **Verified** for server-projected rows; **adopt path preserves** observed name/nameEx | `applyServer`/`fill` vs `adopt` | High |
| Authority hardcoded Customer | **Partially verified**; ADMIN carry-over exists | `authority(Member)` on apply/fill; adopt keeps observed | High |
| Attendance bad timestamp aborts window | **Verified**; open product decision | `TrueFaceDeviceAdapter` javadoc; execution plan G.3 | High |
| F1–V21 fully implemented | **Mostly verified** | Plan status + tests; Section E / physical not done (out of this audit’s scope to re-run) | Medium |

---

## 3. Exact roster-count reconciliation (Reader 1, 15 Oct production)

Definitions (do not mix stages):

| Label | Meaning |
| --- | --- |
| Hardware roster | Users returned by `ListUsers` with count = announced total |
| Photo index | Entries from `ListFaceChecksums` (`nTotalCount`) |
| Observed | Rows in `device_observed_user` after a trusted list |
| Pending undecided | `pending_enrollment` unresolved, no decision |
| Imported members | `member` rows created via import/create |
| Desired present | `desired_member` with `present_on_reader` for a reader |

| Count | Value | Source | Notes |
| --- | --- | --- | --- |
| Hardware roster | 1217 | Gateway log `ListUsers … 1217` | Same reader `c07af6a0…` |
| Photo checksums | 1154 | Log `ListFaceChecksums … 1154` | 1217 − 1154 = **63** users with no photo index entry |
| Members created | 992 | MySQL `member` count (15 Oct evening) | Matches UI |
| Pending, no decision | 225 | MySQL pending on Reader 1 | 63 no photo + 162 with photo waiting / name hold |
| Same-name hold | 162 | Query: face present + name equals existing member | Not lost; Review / create-one-by-one |
| No photo | 63 | Observed `face_sha256` null | Import correctly refuses create without face |
| Arithmetic 1154 − 990 | ~164 | Close to 162 | Report’s “164” is approximate, not a separate category |
| Arithmetic 1217 − 990 | ~227 | Close to 225 pending | Same |

**No silent omission:** skipped import rows remain pending with an explicit conflict reason. They are not deleted.

**How to get every distinct Reader 1 identity onto the server and Reader 2 (plan-compatible):**

1. Photos on server (wanted upload — done).
2. Create or link every pending: either staff Review decisions, or a **separate, named roster-replication import** that creates one member per distinct source `(reader, deviceUserId)` when a face is present, without treating name-only match as identity (Prompt 2). Do **not** use blanket `force=true` that also bypasses “already mapped” / photo-required rules.
3. Desired-state seed/reconcile to Reader 2 (independent ids). “Exact copy” means **same members and canonical fields**, not the same terminal ids.

---

## 4. Field-by-field transformation matrix

| Field | Reader 1 source | Backend | Reader 2 write | Intentional? | Read-back checks |
| --- | --- | --- | --- | --- | --- |
| `deviceUserId` | `szUserID` | Mapping per reader; new id via H+1 on other readers | New id on R2 | **Yes** (plan) | Exact id written |
| Name / nameEx | `szName` / `szNameEx` | Adopt: keep both; server edit: from `fullName` | Truncated 31 / 127 | Adopt preserves; edit merges | Day-independent string equality |
| `validFrom` / `validTo` | Reader clock | Adopt: reader time; membership: day start / **23:59:59** end | `EndOfDay` on write | **Yes (V3)** for membership; adopt keeps reader times | **Day** compare (current) |
| `userStatus` | 0/1 | Member status → freeze | 0/1 | Yes | Exact |
| `authority` | ADMIN / Customer | Adopt: observed; seed/update: ADMIN if any row ADMIN else Customer | Mapped to SDK enum | Partial intentional | Exact (normalized USER↔Customer) |
| door / time section | Observed | Adopt keeps; otherwise defaults 1,1 | Written | Defaults intentional | Exact |
| Face | JPEG bytes | Content SHA-256 of upload | Insert/update bytes | Device may re-encode | **Strict SHA-256** still — risk if firmware re-encodes |

**Exact raw replication of all terminal fields is not the architecture.** Canonical projection (membership window, Customer default, per-reader ids) is documented. Adopt-from-reader path is the closest to “keep what the reader showed.”

---

## 5. Architecture / docs conformance matrix

| Requirement | Status | Gap |
| --- | --- | --- |
| F1–F3, V1–V21 coded + fake-reader tests | Largely yes | Section E physical gates open (plan) |
| V3 end-of-day validity | Implemented | Conflicts with “byte-identical times on both readers” product ask |
| V13 same face ≠ one member | Observations separate; import still **holds** create on face/name match | Hold is intentional (slice 3); full auto-create of all IDs needs separate op |
| V16 one writer / reader | Implemented | Reject same-reader write parallelism |
| V7 multi-reader isolation | Implemented | Report’s “parallelize readers” already true |
| Import pending + Review | Implemented | No import **summary partition** API yet |
| Photo wanted upload | Implemented | Persist checksum→SHA cache across restart (perf only) |
| Attendance bad timestamp | Was stall; **now skip that row** (product yes) | Valid punches continue; search/page failure still fails |
| Offline unlinked copy | **Rejected permanently** | Server-only brain |

---

## 6. Conflicts with execution plan (must not implement as suggested)

| Suggestion | Conflict |
| --- | --- |
| `force=true` bypassing all duplicate checks | Violates slice-3 / conservative import; Remediation Prompt 2 forbids it; risks auto-merging or mass duplicate members |
| Reader 2 must reuse Reader 1 `deviceUserId`s | Violates per-reader allocator and “`deviceUserId` is device-local” |
| Keep exact end times (e.g. 00:00:00) on write | Conflicts with V3 “end of local day 23:59:59” (last-day access) |
| Photo verify = non-empty bytes only | Conflicts with “verify before ack” and Remediation Prompt 3 |
| Concurrent writes to one reader | Conflicts with V16 |
| Under-2-minute sync as acceptance without measurement | Estimates only; do not promise |

---

## 7. Prioritized remediation list (for Prompts 2–4)

| Sev | Item | Root cause | Minimal fix | Tests |
| --- | --- | --- | --- | --- |
| P0 | Incomplete roster create | Name/face hold leaves 162+ pending | Separate **replicate / import-as-distinct** with disposition summary; keep normal import conservative | Distinct IDs same name; same face policy; idempotent re-import |
| P0 | Reader 2 verify / duplicates | Date strictness (fixed); photo SHA may remain; occupied-id retry storm (retry-same-id fixed) | Classified face verify (Prompt 3); keep day compare | Re-encoded vs unrelated face fixtures |
| P1 | Import summary | API returns created/skipped list but UI/ops hard to reconcile | Counts from persisted dispositions | Partition sum = observed |
| P1 | Field fidelity doc | Transformations undocumented for staff | Spec in `execution-plan` / ops | Adopt vs membership window cases |
| P2 | Fence timeout rescan | Was 120 s → Unobserved | **Fixed:** warn @5 min, resend @15 min (`FenceResendAfter`) | 502 / slow observed |
| P2 | HTTP 20 s | Bulk pull timeouts | **Fixed:** HttpClient 60 s on BackendLink + DesiredStateClient | Timeout + retry idempotent |
| P2 | Photo cold-start | Was thought RAM-only | **Already persisted** as `{journal}.faces.json` (`FaceChecksumCache`) | Restart without 1154 downloads |
| P2 | Import lock per row | `lockReaders` each create | **Fixed:** batches of 25 | Mid-batch failure + retry |
| P3 | Log verbiage | Ambiguous messages | Stage/reason/next action | — |
| P3 | Attendance bad row | Threw whole window | **Fixed:** skip messy row only | Mixed valid/invalid batch |

---

## 8. Implementation sequence

1. **Prompt 2:** roster dispositions + optional replicate-as-distinct + reconciliation summary + docs for intentional transforms.  
2. **Prompt 3:** face verification strategy; fence/timeout/backoff; photo cache persistence; bounded photo upload concurrency.  
3. **Prompt 4:** logs; attendance row skip (if approved); full test suites.

Acceptance for “hassle-free load”: every Reader 1 observed user with a face has an auditable disposition; members intended for both readers have desired rows; Reader 2 converges without duplicate ids for the same member; intentional field transforms are documented.

---

## 9. Unresolved / evidence needed

- Whether Dahua `GetFace` bytes after `InsertFace` always differ in SHA-256 on this firmware (log shows mismatch; need classified reason after day-fix).  
- Current production counts after 15–17 Oct staff actions (DB may have changed).  
- Name-only “possible match” → use DISTINCT_SOURCE import or Review (replicate op shipped).
