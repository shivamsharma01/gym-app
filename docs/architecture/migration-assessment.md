# Migration assessment: current device sync → server-authoritative design

Status: assessment only, no implementation proposed.
Target: [gym-device-sync-before-poc-architecture.md](gym-device-sync-before-poc-architecture.md) (cited as "§n").
Branch assessed: `feat/gateway-redesign`.

Path shorthands used below:

- `B/` = `backend/src/main/java/com/example/gym/`
- `BT/` = `backend/src/test/java/com/example/gym/`
- `G/` = `gateway/src/Gym.Gateway/`
- `GA/` = `gateway/src/Gym.Gateway.Adapters/`
- `GT/` = `gateway/tests/Gym.Gateway.Tests/`
- `F/` = `frontend/src/`

## 0. Hardware evidence baseline

Rule applied throughout: a hardware behavior counts as known only if a device run observed it repeatedly. Everything else is **UNKNOWN**, including behavior the current code silently relies on.

| Behavior | Evidence | Status |
| --- | --- | --- |
| SDK login (Windows), user enumeration, event subscription | `docs/device-sdk.md` session log | Observed |
| SDK create / rename / delete of a user | `GYM-VISIT-2026-09-13.md` | Observed (one visit) |
| Remote face insert lets the person in | 09-13: SDK returned true, operators saw entry; one trial | UNKNOWN (single trial) |
| Live door event arrives after a walk | 09-13: no `ALARM_ACCESS_CTL_EVENT` within 90 s | UNKNOWN (one failed observation) |
| Freeze blocks entry (`nUserStatus=1`) | Not reached on 09-13 | UNKNOWN → P1 |
| Linux SDK login | Not run | UNKNOWN (not a sync gate; deployment question) |
| P1–P12 (§18) | Not yet run; harness ready (`--gates`) | UNKNOWN |

Unknowns the current code depends on that §18 did not list were added to the probe as **P13–P19** (see `gateway/tools/Gym.Gateway.SdkProbe/GATES.md`, `GateSuite.cs`):

| New gate | Code that relies on the behavior today |
| --- | --- |
| P13 live door event per walk, `nPunchingRecNo` = stored `nRecNo` | `GA/TrueFaceDeviceAdapter.cs` alarm callback (0x3181 → ACCESS) feeding `DEVICE_EVENT`; `B/device/AttendanceIngestionService.ingest` fingerprint `rec:` |
| P14 face read-back fidelity; INSERT over a photo; UPDATE with none | `GA/TrueFaceDeviceAdapter.GetFace`, face hash comparisons in `G/DeviceChangeWatcher.HandleDetected`, `G/CommandDispatcher.RecordEcho` |
| P15 answers for missing user/photo | `TrueFaceDeviceAdapter` no-photo fallback (0x800004B5 → `HasFace`), `DeviceChangeWatcher.DetectDeletions` |
| P16 user list completeness / repeatability | `DeviceChangeWatcher.ReadTrustedList`, `DetectDeletions` (a short list is read as deletions) |
| P17 `szName` 31 / `szNameEx` 127 storage | `GA/TrueFaceDeviceAdapter` name limits, `GT/TrueFaceNameTests` |
| P18 stored punch times in reader clock | `TrueFaceDeviceAdapter.SynchronizeTime` (sets reader to UTC, line 826), `ToUtc` (line 1503) |
| P19 `emAuthority=Administrators` grants reader admin menu | `B/member` authority (V24), `PUT /api/v1/members/{id}/authority`, §9 admin promotion |

P5 also gained "screen face enroll" trials (does a face enrolled on the reader screen raise an alarm). **Recommendation:** add P13–P19 to §18 when the doc is next revised; the doc itself was not edited.

---

## 1. Backend synchronization components

| Component | Role today | Key methods |
| --- | --- | --- |
| `B/device/GatewayWebSocketHandler` | `/gateway` WebSocket endpoint | `handleTextMessage` (line 51) calls `messageService.process(raw)` **without a bound gateway id**; `registerIfHandshake` (84) binds the session to the gatewayId the message asserts |
| `B/device/GatewayAuthService` | Gateway credential check | `authenticate` (64): no token + no shared token returns SHARED (anonymous). `token_expires_at` never checked |
| `B/device/web/InternalGatewayController` | `/internal/gateway` HTTP fallback | `enroll`, `credentials/rotate`, `devices`, `messages` (`ingest`, 81), `commands`, `faces` GET/POST |
| `B/device/GatewayMessageService` | Inbound message router | `process(raw)` (81) / `process(raw, boundGatewayId)` (85); `resolveDevice` (323) accepts a device not assigned to the reporting gateway |
| `B/device/DeviceSyncService` | Per-command outbox | `supersede` (99), `enqueue` (117), `reclaimStaleDispatched` (151), `dispatchDue` (214), `deliver` (251), `handleResult` (293/304), `withChangeTimes` (508), `failAttempt` (654) |
| `B/device/WebSocketGatewayCommandTransport`, `SimulatedGatewayCommandTransport` | Push commands to gateway / simulator | simulator enabled by `APP_GATEWAY_SIMULATOR_ENABLED` |
| `B/device/GatewayCommandPollService` | HTTP poll fallback | `claimDue` (49), SKIP LOCKED |
| `B/device/GatewaySessionRegistry`, `GatewayConnectedListener` | Live sessions, 30 s ping, catch-up on connect | |
| `B/device/MemberDeviceProvisioningService` | Every member on every device | `provisionMember` (73), `provisionDevice` (97), `markHeldBySource` (118), `reseedEverywhere` (198), `removeEverywhere` (236), `moveToSerial` (292), `completeMove` (326), `ensureMapping` (372) |
| `B/device/DeviceAuthorizationService` | Membership → access window | `window` (61/65), `refresh` (122/133) stamps `accessChangedAt`, `enqueue` (177) |
| `B/device/DeviceUserChangeService` | Applies device-side edits to members | `apply` (126), `importFromReconcile` (288), `createFromDevice` (315), `applyCodeClash` (340), `applyDeletion` (382), `applyMembershipChanges` (468), `markSiblingsHeld` (623), `findMember` (641) |
| `B/device/DeviceReconciliationService` | Snapshot compare | `applyDeviceUserSnapshot` (71) |
| `B/device/DeviceUserImportService` | Bulk import | `importUsers` (78) merges by deviceUserId across devices |
| `B/device/ReconciliationConflictService` | Conflict actions | `resolve` (39): REMOVE / DISMISS only |
| `B/device/DeviceMemberImporter`, `SyncClock` | Import helper, change-time clock | |
| `B/device/AttendanceIngestionService`, `AttendanceLinker` | Punch ingest | `ingest` (56/62) fingerprints `rec:`/`cmp:`; linker rewrites `member_id` on earlier rows |
| Schedulers | OutboxDispatcher 10 s; AutoReconcileScheduler 15 min; AccessCheckScheduler hourly; heartbeat watchdog 90 s; `MembershipSyncListener` | |
| `B/member/Member.getDeviceUserId` (166–168) | deviceUserId = serial / memberCode | identity coupling |
| Config | `SecurityConfig` excludes `/gateway`, `/internal/gateway/**`, `/live` from JWT; `ProdSecurityGuard` does not require a gateway token | |

## 2. Gateway synchronization components

| Component | Role today | Key methods |
| --- | --- | --- |
| `G/GatewayWorker` | Host loop | `ExecuteAsync` (42): send, WebSocket, heartbeat 15 s, poll 2 s while WS down, watcher, time sync, status, reader health; `PublishOutcome` (315); `AcceptCommand` (405) per-device channel |
| `G/BackendLink` | Transport | `SendAsync` (91) always persists to outbox (heartbeats too); `ReplayPendingAsync` (98); `RunWebSocketAsync` (228) awaits replay before `ReceiveLoopAsync` (314); token in `?token=` query; backoff to 60 s |
| `G/DurableOutboundStore` | `outbox/{messageId}.json` files | unbounded |
| `G/CommandDispatcher` | Execute server commands | `DispatchAsync` (56) 30-min in-memory correlation dedupe; `DispatchOnceAsync` (121); `Dispatch` (237); `RecordEcho` (333) |
| `G/DeviceChangeWatcher` (1693 lines) | Poll readers, detect, merge, fan out | `RunAsync` (127) one loop for all readers, ≥15 s; `FlushReportsAsync` (456); `ScanLocked` (565); `ReadTrustedList` (626); `HoldBurst` (765); `DetectDeletions` (957); `TryApplyAsync` (401); `HandleDetected` (1135); `ConvergeUser` (1200) partial writes; `AlignReaders` (1359) reader-to-reader seeding; `FanOutAsync` (1441); `PlausibleDeviceTime` (1635) |
| `G/LocalMemberStore` | `members/members.json` | `Merge` (120), `Wins` (327) latest-timestamp wins |
| `G/RosterStateStore`, `RosterDigest` | `roster/{deviceId}.json`; plain `Dictionary` shared without locks | |
| `G/TimedDeviceAdapter` | Timeouts, DEGRADED after 4 errors, reconnect 10 s → 5 min | |
| `G/CredentialRotationService` | Token rotation | `ExecuteAsync` (36) |
| `GA/TrueFaceDeviceAdapter` | NetSDK calls | `UpsertUser` (864) read-modify-write then INSERT; `GetFace` (237) uses `stuUpdateTime` (line 320); `SynchronizeTime` (819) sets UTC; `EndOfDay` (1064) 23:59:59; `NoAccessDay` 2018; `QueryAttendance` (1177) page 20, returns `[]` on failure; `ToUtc` (1503) falls back to now; alarm map 0x3181 ACCESS, 0x3240/0x3216/0x3218/0x34C2 USER_CHANGED |
| `GA/MockDeviceAdapter`, `DeviceAdapterFactory`, `IDeviceAdapter` | Adapter seam | |
| `GA/RemoteDeviceAdapter` | Simulator client | orphaned since `simulator/` was removed |

## 3. Frontend synchronization, member and device-management components

| Component | Role today | Conflict with target |
| --- | --- | --- |
| `F/lib/api.ts`, `F/lib/types.ts` | REST client, types incl. `deviceSyncState`, conflicts, sync commands | types mirror the per-command model |
| `F/lib/live.ts` `useStaffLive` | `/live` staff WebSocket | keep |
| `F/components/AppShell.tsx` | One status dot aggregating all gateways | hides per-reader state |
| `F/features/members/MemberDetailPage.tsx` | Deactivate → `DELETE /api/v1/members/{id}`; `DeviceSyncPanel` "Read from device" (tooltip "The newer copy wins", line 376), "Send again" | delete ≠ deprovision; timestamp language |
| `F/features/members/MemberPhotoField.tsx` | Photo "sent to every device" | all-devices assumption |
| `F/features/members/MembershipPanel.tsx` | Shows `deviceSyncState` | per-membership sync state replaced by per-reader projection |
| `F/features/devices/DeviceDetailPage.tsx` | Conflicts with DISMISS/REMOVE only (254–272); Import device users; Sync Now; sync-commands tab polled every 15 s; DoorControl | no review actions of §21 |
| `F/features/devices/DevicesPage.tsx`, `DeviceNewPage.tsx` | List / register | keep |
| Missing | Review queue (§21), pending-enrollment approval, side-by-side comparison, audit view | |

## 4. Database tables and entities

Flyway in `backend/src/main/resources/db/migration/`.

| Area | Table (entity) | Migrations | Notes |
| --- | --- | --- | --- |
| Members | `member` (`Member`) | V3, V13, V15–V18, V24, V25 | `*_changed_at` columns (V16, V17, V25) exist only for timestamp merge; serial number V25 drives deviceUserId |
| | `membership` (`Membership.device_sync_state`) | V3 | enum `DeviceSyncState` NOT_SYNCED/PENDING/SYNCED/FAILED/OFFLINE |
| | `member_face`, `gateway_face_upload` | V15 | |
| Device mapping | `member_device_mapping` (`MemberDeviceMapping`) | V4, V15, V25 (`pending_device_user_id`) | unique (device_id, device_user_id) and (device_id, member_id) |
| | `device_user_snapshot` (`DeviceUserSnapshotRow`) | V13 | last observed only, no baseline |
| | `reconciliation_conflict` (`ReconciliationConflict`) | V14 | types EXTRA_DEVICE_USER / MISSING_ON_DEVICE / AUTH_MISMATCH; status OPEN/RESOLVED/DISMISSED |
| Device commands | `device_sync_command` (`DeviceSyncCommand`) | V4 | per-operation queue; statuses PENDING, DISPATCHED, ACKNOWLEDGED (never set), SUCCEEDED, RETRYING, FAILED (never set), DEAD_LETTER, CANCELLED |
| Gateway state | `gateway` (`Gateway`) | V4, V12 | credentials, `token_expires_at` unused |
| | `device` (`Device`) | V4, V26 (`roster_digest`) | |
| | `gateway_message_dedupe` | V14 | inbound idempotency |
| | `security_event` | | |
| Attendance | `attendance_event` (`AttendanceEvent`) | V4, V14 | unique (tenant_id, device_id, fingerprint); `member_id` mutated by `AttendanceLinker` |
| | `attendance_sync_cursor` | V4 | |
| **Absent** | desired projection + revision per reader, baseline, review item, pending enrollment, audit of review decisions | | §5, §7, §8 |

## 5. Synchronization flows today

1. **Server edit → readers.** Member/membership save → `MembershipSyncListener` / `DeviceAuthorizationService.refresh` → `MemberDeviceProvisioningService.provisionMember` → `DeviceSyncService.enqueue` one command per device per operation (with `supersede`) → `withChangeTimes` stamps change times → `dispatchDue` (10 s) → WebSocket or poll → `CommandDispatcher.DispatchAsync` → `TrueFaceDeviceAdapter.UpsertUser` → `SYNC_RESULT` → `handleResult` → mapping SYNCED.
2. **New device registered.** `provisionDevice` seeds every member on it.
3. **Reader edit → server and other readers.** `DeviceChangeWatcher.RunAsync` → `ScanLocked` → `ReadTrustedList` / `HoldBurst` / `DetectDeletions` → `HandleDetected` → `LocalMemberStore.Merge` (latest wins) → `FanOutAsync` writes the change to sibling readers directly → `FlushReportsAsync` sends `DEVICE_USER_CHANGED` → backend `DeviceUserChangeService.apply` (per-field `isAfter` winner, `createFromDevice`, `applyDeletion` deactivates the member and removes everywhere, `applyMembershipChanges`) → `markSiblingsHeld` / `markHeldBySource` mark mappings SYNCED without device confirmation.
4. **Reader-to-reader seeding.** `DeviceChangeWatcher.AlignReaders` copies users from a full reader to an empty one.
5. **Reconciliation.** AutoReconcileScheduler 15 min / `POST /devices/{id}/reconcile` → `RECONCILE_DEVICE` → `RECONCILIATION_RESULT` → `DeviceReconciliationService.applyDeviceUserSnapshot` → auto-import via `importFromReconcile` + conflicts.
6. **Bulk import.** `POST /devices/{id}/import-users` → `DeviceUserImportService.importUsers`, merges by deviceUserId across devices.
7. **Faces.** Upload → `member_face` → `UPSERT_FACE` command, gateway fetches `GET /internal/gateway/faces/{memberId}/{version}`; device-side face → `POST /internal/gateway/faces`.
8. **Serial move.** `moveToSerial` / `completeMove` change deviceUserId on every reader.
9. **Attendance.** Live alarm → `DEVICE_EVENT`, plus `QueryAttendance` backfill → `AttendanceIngestionService.ingest` → `AttendanceLinker`.
10. **Time.** `SynchronizeTime` sets each reader to UTC periodically.
11. **Health.** Heartbeat 15 s (via durable outbox), DEVICE_STATUS, watchdog 90 s.

## 6. Retry, acknowledgement, timeout and reconnect logic

| Where | Logic | Gap against §10/§11 |
| --- | --- | --- |
| `DeviceSyncService.failAttempt` | Backoff 5 s × 2^(n−1), cap 10 min, +1 s jitter; 6 attempts → DEAD_LETTER | Per operation, not per revision; dead letters need manual retry |
| `DeviceSyncService.reclaimStaleDispatched` | DISPATCHED > 2 min → retry, **only when gateway disconnected** | A connected gateway that drops a command leaves it DISPATCHED forever |
| `DeviceSyncService.handleResult(…, skipped)` | `skipped` counts as SUCCEEDED / SYNCED | Ack without verification |
| ACKNOWLEDGED / FAILED states | Never set | Dead states |
| `CommandDispatcher.DispatchAsync` | 30-min in-memory dedupe by correlationId | Lost on restart; not revision based |
| `BackendLink.RunWebSocketAsync` | Replays entire outbox before receiving; backoff to 60 s | Heartbeats replayed as business messages; reconnect does not observe readers first (§10) |
| `BackendLink.SendAsync` / `DurableOutboundStore` | Every message persisted, unbounded | §22 unbounded heartbeat durability |
| `GatewayWorker.ExecuteAsync` | Poll 2 s while WS down | Duplicates WebSocket path |
| `TimedDeviceAdapter` | Per-call timeout, DEGRADED after 4 errors, reconnect 10 s → 5 min | Retain; timeouts unverified (P10) |
| `GatewaySessionRegistry` | 30 s ping; watchdog 90 s | Retain |
| `GatewayConnectedListener` | Catch-up dispatch on connect | Must become pull-after-revision |
| `TrueFaceDeviceAdapter.QueryAttendance` | Returns `[]` on failure | Failure indistinguishable from "no punches" |

## 7. Assumptions that conflict with the new architecture

| # | Current assumption | Where | Conflicts with |
| --- | --- | --- | --- |
| C1 | Anonymous gateway allowed when no shared token | `GatewayAuthService.authenticate`; `ProdSecurityGuard`; deploy default `APP_GATEWAY_SHARED_TOKEN=""` | §15, §22 |
| C2 | Gateway id is self-asserted; any device accepted | `GatewayWebSocketHandler.registerIfHandshake`, `handleTextMessage`; `GatewayMessageService.resolveDevice` | §15 |
| C3 | Latest timestamp wins | `LocalMemberStore.Wins`; `DeviceUserChangeService.apply` (`isAfter`); `DeviceSyncService.withChangeTimes`; `member.*_changed_at` | §2, §7, §22 |
| C4 | `stuUpdateTime` orders changes | `TrueFaceDeviceAdapter.GetFace` line 320; `DeviceChangeWatcher.PlausibleDeviceTime`, `HandleDetected` | §2 (telemetry only); P4 UNKNOWN |
| C5 | deviceUserId = serial/memberCode; tenant-wide serial fallback | `Member.getDeviceUserId`; `DeviceUserChangeService.findMember`; `ensureMapping` | §5, §22 |
| C6 | Every member on every reader | `provisionMember`, `provisionDevice`, `reseedEverywhere`; `docs/product.md` | per-reader desired projection §5 |
| C7 | Device-created users auto-become members | `createFromDevice`, `importFromReconcile`, `importUsers` | §8 pending enrollment |
| C8 | Device delete deactivates member everywhere | `applyDeletion` → `removeEverywhere` | §9, §20 "Reader deletes user" |
| C9 | Device edits change memberships | `applyMembershipChanges` | server authoritative §2 |
| C10 | Gateway fans out business changes reader-to-reader | `FanOutAsync`, `AlignReaders` | §22 |
| C11 | SYNCED without device confirmation | `markHeldBySource`, `markSiblingsHeld`; `handleResult` skipped | §10 verify-then-ack |
| C12 | Partial field writes | `ConvergeUser`; `UpsertUser` read-modify-write | §10 full logical record (P3) |
| C13 | 15 s full-roster polling of all readers in one loop | `DeviceChangeWatcher.RunAsync` | §12 |
| C14 | Heartbeats durable and replayed | `BackendLink.SendAsync` | §11, §22 |
| C15 | Deactivate = DELETE | `MemberController` `@DeleteMapping("/{id}")`; `MemberDetailPage` | freeze ≠ remove §6 |
| C16 | Freeze blocks the door | `DISABLE_USER`, `docs/product.md` | P1 UNKNOWN |
| C17 | Reader runs in UTC; end of day 23:59:59 UTC | `SynchronizeTime`, `EndOfDay`, `ToUtc` | P11, P18 UNKNOWN |
| C18 | Live alarm per punch carries the record number | `AttendanceIngestionService` `rec:` fingerprint | P13 UNKNOWN (09-13 saw none) |
| C19 | A shorter user list means deletions | `DetectDeletions` | P16 UNKNOWN |
| C20 | Attendance rows mutable | `AttendanceLinker` | §14 append-only |
| C21 | Simulator transport in prod config | `SimulatedGatewayCommandTransport`, `APP_GATEWAY_SIMULATOR_ENABLED` | simulator removed |

## 8. Code to retain

- Gateway enrollment and credential issuing: `InternalGatewayController` `enroll`, `credentials/rotate`; `G/CredentialRotationService`; `GatewayCredentialIT`. Auth checks must be tightened (C1, C2).
- `GatewaySessionRegistry` (ping), heartbeat watchdog, `StaffLiveWebSocketHandler` / `F/lib/live.ts`.
- `gateway_message_dedupe` idempotency concept.
- `G/TimedDeviceAdapter` (timeouts, DEGRADED, reconnect).
- `GA/IDeviceAdapter`, `DeviceAdapterFactory`, `MockDeviceAdapter`; `TrueFaceDeviceAdapter` low-level marshalling (login, enumeration, face GET/INSERT, name handling), but not its merge logic.
- `G/CommandDispatcher` per-device serialization (one worker per reader is the target, §11).
- `B/device/GatewayCommandPollService` SKIP LOCKED claim pattern, reusable for revision pulls.
- `member_device_mapping` table and its two unique constraints.
- Face storage (`member_face`, `FaceStorageService`), `GET/POST /internal/gateway/faces`.
- `attendance_event` idempotent ingest (unique fingerprint) and `attendance_sync_cursor`.
- `F/features/devices/DevicesPage`, `DeviceNewPage`, DoorControl; member CRUD pages minus sync panel semantics.
- `gateway/tools/Gym.Gateway.SdkProbe` (POC harness).

## 9. Code to replace

| Current | Replaced by (target concept only) |
| --- | --- |
| `DeviceSyncService` per-operation outbox, `device_sync_command` | Per-reader desired projection + monotonic revision (§10) |
| `MemberDeviceProvisioningService` | Desired-state projector (per reader, not all) |
| `DeviceUserChangeService` | Observation ingestion → three-way compare → review items / pending enrollments |
| `DeviceReconciliationService`, `ReconciliationConflictService`, `reconciliation_conflict` | Three-way reconciliation and review queue (§7, §21) |
| `DeviceUserImportService`, `DeviceMemberImporter` | Bootstrap mapping/review tooling (§13, M5) |
| `G/DeviceChangeWatcher` | Per-reader worker: coalesced scans, alarm = scan trigger, observe → pull → apply → verify → ack |
| `G/LocalMemberStore`, `RosterStateStore`, JSON files | SQLite journal (§11) |
| `G/BackendLink` outbox semantics, `DurableOutboundStore` | Bounded durable business messages; telemetry not durable |
| `TrueFaceDeviceAdapter.UpsertUser` partial RMW | Full logical-record write, face separate (subject to P3, P14) |
| `CommandDispatcher` in-memory dedupe | Applied-revision persisted in journal |
| `AttendanceLinker` mutation | Append-only resolution at read time (§14) |
| `F/.../DeviceDetailPage` conflicts tab, `DeviceSyncPanel`, `MembershipPanel` sync state | Review queue + per-reader projection status |
| `F/components/AppShell.tsx` single dot | Per-gateway / per-reader health |

## 10. Code to delete after migration (after cutover M7)

- Timestamp merge: `LocalMemberStore.Wins`, `DeviceSyncService.withChangeTimes`, `DeviceUserChangeService` `isAfter` paths, `SyncClock`, `member.*_changed_at` columns (V16, V17, V25 parts).
- `DeviceChangeWatcher.FanOutAsync`, `AlignReaders`, `PlausibleDeviceTime`.
- `MemberDeviceProvisioningService.markHeldBySource`, `DeviceUserChangeService.markSiblingsHeld`, `createFromDevice`, `applyDeletion`, `applyMembershipChanges`, `findMember` serial fallback.
- `Member.getDeviceUserId` serial coupling; `moveToSerial` / `completeMove`.
- `GatewayAuthService` SHARED/anonymous branch; `APP_GATEWAY_SHARED_TOKEN` fallback.
- `SimulatedGatewayCommandTransport`, `APP_GATEWAY_SIMULATOR_ENABLED`; `GA/RemoteDeviceAdapter` and `GT/RemoteDeviceAdapterTests` (already orphaned; can go now).
- Command types made redundant by projections: `ENROLL_FACE` (legacy), `REPORT_DEVICE_USER`, `REFRESH_DEVICE_USERS`, `RECONCILE_DEVICE` (as push command), `CLEAR_DEVICE_LOGS` (destructive; decision needed, E7).
- `DeviceSyncState` on `membership`; dead statuses ACKNOWLEDGED/FAILED.
- Gateway HTTP poll loop in `GatewayWorker.ExecuteAsync` if pull-over-WebSocket covers it (decision, E5).
- `docs/product.md` statements: every member on every device, later change wins, device delete deactivates, freeze blocks entry.

## 11. APIs and WebSocket messages that must change

| Item | Today | Change needed (what, not how) |
| --- | --- | --- |
| `/gateway` handshake | `?token=` optional; self-asserted id | Mandatory credential; gateway id from credential, not message |
| `REGISTER_GATEWAY` | binds asserted id | Bound to authenticated identity; reports applied revision per reader |
| `HEARTBEAT`, `DEVICE_STATUS` | durable, replayed | Telemetry only |
| Server → gateway commands (`CREATE_USER` … `UPSERT_FACE`, 17 `SyncCommandType`s) | Pushed per operation | Replaced by "changes after revision R for reader X" pull and revision ack |
| `SYNC_RESULT` | per correlationId, `skipped` = success | Ack carries reader, revision, verified observation |
| `DEVICE_USER_CHANGED` | applied as authoritative edit | Becomes observation (baseline/observed), creates review/pending enrollment |
| `RECONCILIATION_RESULT` | triggers auto-import | Observation snapshot with scan trigger metadata |
| `ENROLLMENT_RESULT` | face enrollment | Pending enrollment record |
| `DEVICE_EVENT` | attendance with fingerprint | Event identity `(deviceId, recNo)` candidate (P6/P13); time-window backfill (P7) |
| `DEVICE_ALARM` | raw | Scan trigger only |
| `/internal/gateway/commands` (poll) | per-command claim | Revision pull, or removed |
| `/internal/gateway/messages` | unauthenticated fallback possible | Same mandatory auth |
| `GET/POST /api/v1/sync-commands`, `/{id}/retry`, `/{id}/cancel` | operator retries | Per-reader projection status / repair |
| `/api/v1/members/{id}/device-sync` (`retry`, `read`) | "newer copy wins" | Per-reader desired vs observed; read = request observation |
| `DELETE /api/v1/members/{id}` | deactivates + removes everywhere | Separate freeze / remove-from-readers / archive |
| `PUT /api/v1/members/{id}/authority` | pushes authority | Projected ADMIN (P19) |
| `/api/v1/devices/{id}/reconcile`, `/sync-now`, `/import-users`, `/conflicts`, `/conflicts/{cid}/resolve` | auto-import, DISMISS/REMOVE | Review queue endpoints with §21 actions; bootstrap tooling |
| New | — | Review items, pending enrollments, mapping link/unlink, audit |

## 12. Database migrations required (descriptive)

1. Per-reader desired projection with monotonic revision; applied revision per reader.
2. Baseline (last reconciled) and observed snapshot per (device, deviceUserId), replacing or extending `device_user_snapshot`.
3. Review items and pending enrollments with decisions and audit.
4. `member_device_mapping`: decouple deviceUserId from serial; add mapping state (pending/active/conflict); backfill from existing rows.
5. `member`: separate access state (frozen) from presence/archived; later drop `*_changed_at`.
6. `membership.device_sync_state`: drop after UI no longer reads it.
7. `device_sync_command`: freeze, then drop after cutover (keep for rollback window).
8. `reconciliation_conflict`: migrate OPEN rows into review items, then drop.
9. `gateway`: enforce credential presence; use `token_expires_at`.
10. `attendance_event`: add `rec_no` candidate unique key (conditional on P6/P13); stop mutating `member_id`.
11. `AbstractIntegrationTest.resetDatabase` must follow every table change.

## 13. Tests that become invalid

| File | Tests | Reason |
| --- | --- | --- |
| `BT/FaceSyncIT` | most (timestamp winner, auto-import, fan-out) | C3, C6, C7 |
| `BT/DeviceSyncIT` | per-command outbox, all-devices provisioning parts | C6, §10 |
| `BT/SyncReliabilityIT` | `registeringAGatewayRunsTheCatchUpStep` (unenrolled gateway) and catch-up parts | C1, C2 |
| `BT/DeviceRosterImportIT` | auto-import / cross-device merge | C7 |
| `BT/AttendanceLinkingIT` | linker mutating rows | C20 |
| `BT/AccessWindowIT` | device dates applied to memberships | C9 |
| `GT/LocalSyncTests` | most (fan-out, timestamp, `AlignReaders`) | C3, C10 |
| `GT/FaceSyncTests` | `Read_from_device_reports_an_unchanged_photo_with_its_original_time`, `Empty_reader_next_to_a_full_one_gets_its_users`, `Reader_saying_no_photo_never_removes_a_known_photo`, `User_already_known_from_another_reader...` | C4, C10, P15 |
| `GT/TrueFaceUpsertTests` | partial-mutation cases | C12 |
| `GT/RemoteDeviceAdapterTests` | all | simulator removed |
| `frontend/e2e/smoke.spec.ts` | sync panel / conflicts expectations | UI replaced |

## 14. Tests to retain

- `BT/GatewayCredentialIT`, `GatewaySessionRegistryTest`, `ProdSecurityGuardTest` (extend), `StaffLiveHandshakeInterceptorTest`.
- Attendance idempotency tests; `theSameDeviceUserIdOnTwoReadersStaysWithEachReadersMember`.
- Non-sync ITs: `AuthAndSecurityIT`, `MemberListIT`, `MembershipFlowIT`, `ObservabilitySecurityIT`, `Phase6IT`, `Phase7SecurityIT`, `Phase9IT`, `RateLimitIT`, `RbacSeedIT`, `TenantIsolationIT`, `ProdBootstrapSeederTest`, `MemberCoverageTest`.
- `GT/CommandDispatcherTests` (serialization parts), `TrueFaceNameTests` (pending P17), `EnvelopeTests`, `GatewayOptionsTests`, `ConfigAndRotationTests`, `MockDeviceAdapterTests`, `TrueFaceAdapterGuardTests`, digest tests.
- `frontend/.../backendUrls.selftest.ts`; SdkProbe `--self-test`.

## 15. Missing tests

- Every §20 scenario as a state-transition test asserting canonical state, each reader projection, mapping, review/audit and revision.
- Gateway auth: no token rejected; expired token rejected; gateway cannot report for a device it does not own; message gatewayId ignored in favor of credential.
- Revision: stale revision ignored; ack only after verification; replay after gateway restart has no duplicate effect.
- Reconnect order: observe before apply.
- SQLite journal crash/restart.
- One reader offline does not block others.
- Coalesced scan + alarm-triggered scan; a short/failed user list never becomes deletions (P16).
- deviceUserId collision → mapping conflict, no overwrite.
- Freeze vs remove distinct; ADMIN projection (P19).
- Factory-reset reader rebuild (P12).
- Attendance append-only, `(deviceId, recNo)` identity, backfill window, `QueryAttendance` failure surfaced (not `[]`).
- Frontend: review queue actions, pending enrollment approval (none exist; no frontend unit tests, e2e not in CI).
- CI: no backend workflow; only `gateway-msi.yml`.

---

## A. Architecture-to-code gap matrix

| Target (§) | Current code | Gap | Hardware dependency |
| --- | --- | --- | --- |
| Mandatory gateway auth (§15) | `GatewayAuthService.authenticate` anonymous; `registerIfHandshake` | Missing | none |
| Server-authoritative desired state (§2, §5) | Per-command outbox `DeviceSyncService` | Missing | none |
| Per-reader projection (§5) | `provisionMember` all readers | Missing | P10 (capacity) |
| Monotonic revision + pull/ack (§10) | Push + `handleResult` | Missing | none |
| Verify-then-ack (§10) | `markHeldBySource`, `skipped` = success | Contradicts | P3, P14, P15 |
| Three-way reconcile (§7) | Two-way snapshot `applyDeviceUserSnapshot` | Partial (no baseline) | P4, P16 |
| Review queue (§21) | DISMISS/REMOVE conflicts | Missing | none |
| Pending enrollment (§8) | `createFromDevice` auto-create | Contradicts | P2, P9 |
| publicId identity; mapping only (§5) | `Member.getDeviceUserId` serial; `findMember` fallback | Contradicts | P2 |
| Full logical record writes (§10) | `UpsertUser` RMW, `ConvergeUser` partial | Partial | P3 |
| Freeze ≠ remove (§6) | DELETE deactivates + removes | Contradicts | P1 |
| ADMIN projection (§9) | authority push exists | Partial | P19 |
| No timestamp ordering (§2) | `Wins`, `isAfter`, `stuUpdateTime` | Contradicts | P4 |
| SQLite journal, one worker per reader (§11) | JSON files, single watcher loop | Missing | none |
| Coalesced scans; alarms as triggers (§12) | 15 s full polling | Contradicts | P5, P10, P16 |
| Telemetry not durable (§11) | heartbeats in outbox | Contradicts | none |
| Bootstrap without master (§13) | `importUsers`, `AlignReaders` | Contradicts | P2, P9, P12 |
| Attendance by window, recNo identity, append-only (§14) | fingerprint + linker mutation | Partial | P6, P7, P8, P13, P18 |
| Validity boundaries / time zone (§6) | UTC reader, 23:59:59 | Unverified | P11, P18 |
| Factory reset repair (§16) | none | Missing | P12 |
| Observability per reader (§17) | single dot | Partial | none |

## B. Migration phases

These follow §19 M0–M7, ordered by what the current code forces.

- **Phase 0: POC (M0).** Run `--gates` P1–P19 on the gym reader (spare reader for P6 full log / P12). Record verdicts in §18.
- **Phase 1: Security hardening.** Mandatory gateway credential, bound identity, device ownership check, token expiry. Independent of the hardware results.
- **Phase 2: Canonical model (M1).** Projection, revision, baseline, review, pending enrollment tables; mapping decoupled from serial. Old paths still run.
- **Phase 3: Gateway persistence (M2).** SQLite journal, per-reader worker, bounded durable messages, telemetry split.
- **Phase 4: Desired-state sync (M3).** Revision pull/apply/verify/ack, running in shadow next to the outbox.
- **Phase 5: Observations and review (M4).** `DEVICE_USER_CHANGED` / snapshots become observations, review UI, pending enrollment; timestamp merge turned off.
- **Phase 6: Bootstrap migration (M5).** Map existing gym readers' users to members; ambiguities go to review.
- **Phase 7: Attendance (M6).** Cursor/backfill per P6–P8, P13, P18; append-only.
- **Phase 8: Cutover and deletion (M7).** Disable the outbox, fan-out and timestamp paths; keep a rollback switch for a window; then delete §10 items and drop tables.

## C. Dependencies between phases

- Phase 0 blocks claims, not the scaffolding. Phases 1–3 can start before the POC. Phase 4 apply/verify semantics need P3, P14, P15 (and P1 for freeze). Phase 5 needs P4, P5, P16 (scan triggers, deletion detection) and P2, P9 (provisional ids, duplicate faces). Phase 7 needs P6, P7, P8, P13, P18.
- Phase 2 is required by phases 4, 5 and 6.
- Phase 3 is required by phase 4 (applied revision must survive restart).
- Phase 1 should land before phase 4 exposes the revision pull (it must not be anonymous).
- Phase 5 (review UI) is required by phase 6 (bootstrap produces review items).
- Phase 8 requires shadow parity from phases 4 and 5 and an empty review backlog for ambiguous identities.

## D. Risks

1. **Freeze may not block the door (P1).** If NOT OBSERVED, disabled members keep entry; access enforcement needs a removal-based fallback.
2. **Anonymous gateway access is live today** (C1, C2): any client reaching `/gateway` with no shared token configured can report or receive data. Highest-priority non-hardware risk.
3. **Identity coupling** (C5): existing readers store serial-based deviceUserIds; decoupling needs a careful backfill and must not rewrite existing gym users.
4. **Silent data loss during transition** if timestamp merge and observation ingestion both run (double application).
5. **Deletion false positives** if a user list is short or fails (P16 UNKNOWN; current `DetectDeletions` treats it as deletes).
6. **Time zone errors** in validity and attendance (P11, P18; reader forced to UTC today).
7. **Attendance gaps** if retention (P8) is shorter than outages, or if no "after recNo" query exists (P7).
8. **Face fidelity** (P14): if the reader re-encodes photos, hash-based change detection gives false changes.
9. **Duplicate faces across ids** (P9) could break provisional enrollment propagation.
10. **ADMIN on readers** (P19): projecting ADMIN to all readers widens who can open reader menus.
11. **No backend CI and no frontend tests**: regressions during the migration would go unnoticed.
12. **Rollback**: dropping `device_sync_command` / `*_changed_at` too early removes the rollback path.

## E. Questions needing human decisions

1. If P1 is NOT OBSERVED (freeze does not block): should "access disallowed" be enforced by removing the user from the reader (losing the on-reader face) or by an expired validity window (depends on P11)?
2. Which readers get which members (per-reader projection rule): all active members, by branch, or chosen per member?
3. What should the existing serial-based deviceUserIds become: keep them as mapping values forever, or migrate to new ids (which rewrites gym users)?
4. Who may approve review items and pending enrollments (role), and is there an SLA after which a pending enrollment is rejected?
5. Keep the HTTP poll fallback (`/internal/gateway/commands`) or require WebSocket only?
6. Is attendance in scope for the first release (M6 marked optional)?
7. Keep `CLEAR_DEVICE_LOGS` at all, given it destroys reader data?
8. Should the reader clock stay on UTC or local time (affects screen display, validity boundaries, P11/P18)?
9. Should ADMIN be projected to every reader or only the reader where it was granted (P19)?
10. Length of the rollback window before deleting the old outbox and timestamp columns.
11. Should `docs/product.md` be rewritten now (it asserts behaviors this design removes) or at cutover?
12. Should P13–P19 be added to §18 of the architecture doc?
