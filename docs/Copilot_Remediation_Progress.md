# Copilot remediation progress

Outcome of the Oct 2026 Copilot remediation sequence. Durable record: this file + `docs/Copilot_Remediation_Audit.md`. Source prompt drafts (`Prompt 1.md`, `Prompt 2.md`, `Copilot_Remediation_Prompts.md`) were removed after implementation.

| Phase | Status | Notes |
| --- | --- | --- |
| Prompt 1 — Audit (no product code) | **Done** | Audit written 17 Oct 2026 |
| Prompt 2 — Roster complete / field-faithful | **Done** | Distinct-source import + summary + docs + tests |
| Prompt 3 — Face verify / recovery / perf | **Done** | Face classify, fence probe/resend, cache, parallel want-upload |
| Prompt 4 — Logs / attendance / final verify | **Done** | Logs + attendance skip (product yes) + import batches; suites to re-run after this pass |

## Execution-plan conflicts (do not implement)

See audit §6. Short list:

| Suggestion | Conflict |
| --- | --- |
| Blanket `force=true` | Violates slice-3 / conservative import |
| Same `deviceUserId` on both readers | Device-local ids; allocator per reader |
| Presence-only photo ack | Violates verify-before-ack + Prompt 3 |
| Concurrent writes on one reader | Violates V16 |
| Undo V3 end-of-day 23:59:59 | Intentional last-day access |
| Offline gateway copy to another reader | Rejected permanently (server-only brain) |

## Prompt 2 checklist

| # | Item | Status |
| --- | --- | --- |
| 2.1 | Identity: source key `(deviceId, deviceUserId)`; face match ≠ auto-merge | **Done** |
| 2.2 | Distinct-source import (name-only match creates; face match still held) + keep conservative default import | **Done** |
| 2.3 | Reconciliation summary counts from persisted outcomes | **Done** (`Imported.summary` + `GET import-summary`) |
| 2.4 | Document intentional transforms (validity, authority, ids) | **Done** (execution-plan import section) |
| 2.5 | Safer import locking (bounded, resumable) | **Done** — batches of 25 with one `lockReaders` per batch |
| 2.6 | Tests for distinct IDs / same name / idempotent re-import | **Done** (`ReaderImportDistinctIT` 3) |
| 2.7 | Frontend: clear action + summary | **Done** |

## Prompt 3 checklist

| # | Item | Status |
| --- | --- | --- |
| 3.1 | Classified face verification (not presence-only) | **Done** — `SameHash` or readable JPEG (`IsJpeg`); journal stores read-back SHA; empty/corrupt fail with `verification failed (reason=…)` |
| 3.2 | Fence/timeout without endless full rescan | **Done** — warn at 5 min while probing same observation; full resend only after `FenceResendAfter` 15 min; clearer stage=fenced logs |
| 3.3 | Persist photo checksum→SHA cache | **Done** (pre-existing) — `FaceChecksumCache` → `{journal}.faces.json` via `GatewayWorker` |
| 3.4 | Bounded concurrent photo upload | **Done** — gather bytes serially (one-writer), `Parallel.ForEachAsync` upload MaxDegree=4 |
| 3.5 | HTTP timeouts for bulk paths | **Done** — `BackendLink` / `DesiredStateClient` HttpClient 20s → 60s |

## Prompt 4 checklist

| # | Item | Status |
| --- | --- | --- |
| 4.1 | Log verbiage / stage summaries | **Done** (partial) — stage=enumerate / want-photos / fenced; verification reason codes |
| 4.2 | Attendance malformed-row policy | **Done** — skip messy `stuTime` only; valid punches continue (product yes 17 Oct 2026) |
| 4.3 | Full suite + doc sync | **Done for this pass** — gateway 235 passed; `ReaderImportDistinctIT` passed; attendance skip still blocked |

## Already in codebase (pre-audit; do not re-do)

- Wanted-face upload path  
- Checksum paging stop-at-total  
- Validity read-back by day  
- ADMIN authority carry-over  
- `closeLeft` for undecided people gone from reader  
- Observe-set bulk known cache  
- Attendance outer-tx / batch size fixes  
- Retry same id after failed read-back (occupied storm)

## Ops note (hassle-free R1→server→R2)

1. Use **Import as distinct sources** (`DISTINCT_SOURCE`) when cloning a full reader roster; keep conservative import for day-to-day.  
2. Resolve face-held / no-photo rows via Review / bootstrap (still explicit dispositions).  
3. Desired state + one writer per reader converge R2; deviceUserIds will differ — that is correct.  
4. Deploy gateway with this tree for fence/photo/verify changes; no production wipe.
