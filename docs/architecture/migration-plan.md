# Migration plan

Status: approved high-level implementation plan. Frozen 7 October 2026.
Architecture: [gym-device-sync-before-poc-architecture.md](gym-device-sync-before-poc-architecture.md).
Evidence: [device-poc-results.md](device-poc-results.md), run `sync-gates-20261007-130047`, reader serial `TW30000005250265`.
Audit: [migration-assessment.md](migration-assessment.md).

This plan does not change the approved architecture. Where the pre-POC architecture text disagrees with the POC, this plan follows the POC.

The gateway stays Windows-only.

## Rules for every milestone

- The server decides member identity, access, and conflicts. The gateway executes, stores observations, and retries. It does not choose a winner.
- Ordering uses the desired revision and the last reconciled baseline. `stuUpdateTime` stayed zero on this reader, so it is not a clock.
- `deviceUserId` is a per-reader mapping key. It is never a member id. Existing reader ids stay as mapping values. This plan does not rewrite them.
- A device-side create, edit, or disappearance is stored and shown. It is not dropped and it is not auto-merged.
- Only the measured reader behavior is implemented. Unproven items stay out: record numbers surviving power loss or a full log, a retention cap, SDK auto-reconnect as a rule, live `ALARM_ACCESS_CTL_EVENT` as attendance, `afterRecNo` queries, in-place factory reset, and `emAuthority` as menu permission.
- Attendance is the isolated log poll in the architecture. No new reporting or visit-pairing product.
- The first projector may still place every current member on each reader of that gym. That is data in the per-reader projection, so a later rule can narrow it without another sync design.

## Where old and new code overlap

| Milestone | Overlap | Why |
| --- | --- | --- |
| M1 Auth | New credential check replaces anonymous access immediately | Keeping anonymous access beside a new check preserves the hole. |
| M2 Canonical model | New tables exist while the old outbox still runs | Nothing on the reader changes yet. |
| M3 Gateway journal | Journal is written beside the JSON stores; the old watcher still applies member changes | The journal is durability, not a second decision engine. |
| M4 Desired state | Shadow mode computes and logs. A per-reader flag then makes the projection the only writer | Both paths writing the same user would fight. |
| M5 Observations | Clean replacement on a reader once its flag is on | Timestamp merge, fan-out, and auto-import beside review would apply the same edit twice or hide it. |
| M6 Bootstrap | New review queue is the only import path | The old import merges by `deviceUserId` across readers. |
| M7 Attendance | New poll writes append-only rows. Live `DEVICE_EVENT` ingest for punches stops | The POC showed those events do not arrive. Leaving both would double-count if one ever did. |
| M8 Cutover | Old sync code is deleted after shadow parity | Rollback until then is the per-reader flag, not a permanent second architecture. |

## M1 — Mandatory gateway authentication

1. **Objective.** A gateway connection is accepted only with a valid per-gateway credential. Gateway id comes from that credential. Token expiry is enforced.
2. **Backend changes.** Remove the anonymous branch in `GatewayAuthService`. Ignore a gateway id asserted in the message body. Reject a device the credential's gateway does not own. Enforce `token_expires_at`.
3. **Gateway changes.** Send only the issued credential. Rotation keeps using `CredentialRotationService`. No Linux build.
4. **Frontend changes.** None, except failed gateway connections surface as auth failures rather than a silent offline reader.
5. **Database changes.** No new tables. Existing `gateway` credential columns become required for a live connection.
6. **API/message contract changes.** `/gateway` and `/internal/gateway/**` reject a missing or expired credential. `REGISTER_GATEWAY` no longer binds a self-asserted id.
7. **Tests.** No token rejected. Expired token rejected. Wrong gateway cannot report a device. Message-body gateway id is ignored. Extend `GatewayCredentialIT` and `ProdSecurityGuardTest`.
8. **Migration considerations.** Issue credentials to the Windows gateway before deploy. A deploy with an empty shared token currently lets anyone in; that deploy stops working until the real credential is installed.
9. **Rollback considerations.** Roll back the service build. Do not keep a runtime flag that turns anonymous access back on.
10. **Dependencies.** None. This lands before any projection pull.
11. **Acceptance criteria.** An unauthenticated connection cannot register, poll, push, or ingest. A valid gateway can, and only for its own devices. Expired tokens fail closed.

## M2 — Canonical model

1. **Objective.** Add the server's desired projection, baseline, observed snapshot, review item, and pending enrollment, without switching the live reader path.
2. **Backend changes.** New services write these records. Member identity stays `publicId`. Mapping is `(deviceId, deviceUserId) → member`. Access-disallowed becomes freeze (`nUserStatus=1`), which the POC showed blocks the door and keeps the face. Remove-from-reader is a separate desired absence. Do not project `emAuthority`. If a reader later reports an authority field change, M5 stores it as an observation and does not treat it as menu rights.
3. **Gateway changes.** None required for this milestone.
4. **Frontend changes.** None required. The old sync panels stay until M5.
5. **Database changes.** Per-reader desired projection with a monotonic revision. Applied revision per reader. Baseline and observed snapshot per `(device, deviceUserId)`. Review item and pending enrollment with actor, prior state, chosen state, and resulting revision. Mapping gains pending/active/conflict and is backfilled from current rows without changing the ids already on the reader. Member gains an explicit frozen flag distinct from archived. `*_changed_at` stays in the database until M8 and is not read by the new services.
6. **API/message contract changes.** No gateway protocol change yet. Internal services can read the new model in tests.
7. **Tests.** Invariants: no join of `deviceUserId` to member code or serial; one active mapping per `(device, deviceUserId)` and per `(device, member)`; revision only increases; freeze does not delete the member or the mapping. `AbstractIntegrationTest.resetDatabase` includes the new tables.
8. **Migration considerations.** Backfill mappings from existing `member_device_mapping`. Do not push a rewrite of reader user ids. Ambiguous rows are marked conflict and left for M6, not auto-resolved.
9. **Rollback considerations.** New tables are unused by production paths. Rolling back the app leaves the tables in place; they are unused, not a second writer.
10. **Dependencies.** M1 is not required to create tables. M1 is required before M4 exposes them on the wire.
11. **Acceptance criteria.** Tests prove the invariants. The running gym path is unchanged: the old outbox still provisions readers.

## M3 — Gateway journal and one worker per reader

1. **Objective.** Replace JSON execution state with a SQLite journal and one worker per reader, while the old watcher is still the code that changes members.
2. **Backend changes.** None.
3. **Gateway changes.** SQLite holds the reader snapshot, applied revision cursor, outbound business journal, provisional enrollment, attendance cursor, and retry metadata. Heartbeats stay in memory and are sent as telemetry, not written to the replay journal. One worker owns each reader's SDK session. Shared mutable roster dictionaries go away. Restart reloads the journal. The old `DeviceChangeWatcher` merge and fan-out stay in place for this milestone and are the only member writer. The journal records what that worker did; it does not decide conflicts.
4. **Frontend changes.** None.
5. **Database changes.** None on the server. Gateway-local SQLite only.
6. **API/message contract changes.** Heartbeats are no longer durable business messages. The Windows service still speaks the old command protocol.
7. **Tests.** Kill and restart the gateway with a pending outbound business record: it is delivered once. A heartbeat is not replayed as a member change. Two readers: one reader failing does not stop the other worker. Existing `LocalSyncTests` that assert fan-out stay until M5 and are not re-homed onto the journal.
8. **Migration considerations.** On first start, import the current JSON roster snapshot into SQLite, then stop writing the JSON member store. Keep the JSON files on disk until one successful restart proves the journal.
9. **Rollback considerations.** A flag selects JSON or SQLite for the execution log. Member decisions remain the old watcher either way, so rollback does not change who wins a conflict.
10. **Dependencies.** None on M2. M4 needs this journal for the applied revision.
11. **Acceptance criteria.** Restart and a dropped network do not duplicate a business send and do not lose a queued observation. Health traffic is not in the business journal.

## M4 — Desired-state apply, verify, and ack

1. **Objective.** The gateway pulls a reader's desired revision and applies it with a full user write and a separate face write. A stale revision cannot roll the reader backward.
2. **Backend changes.** Same transaction as a canonical member write updates the affected readers' desired projections and bumps the revision. Wake the gateway with a notification that names the reader and the revision, not the full member. Serve a paged pull of desired state after a given revision. Accept an ack only for a revision the gateway verified.
3. **Gateway changes.** Reconnect order is fixed: authenticate, reader health, read the reader, send observations, pull desired state, apply, verify, ack. User apply sends the full logical record, including validity begin and end on the reader-local clock. End of the reader's local day is `23:59:59`. Do not call `SynchronizeTime` to force UTC. Face replace is `UPDATE`, or remove then `INSERT`. A second `INSERT` over an existing photo fails with `PHOTO_EXIST` and is not success. An `UPDATE` with no photo is not a delete. Verify by reading the user and the face back. Compare the face hash to the bytes read back; a padded file above the original photo was re-encoded, so the sent file's hash is not the proof. Missing user is fail code `NO_RECORD`. Missing photo is fail code `UNKNOWN` with SDK error `0x800004B5`. Those two are not the same result. Names: write `szNameEx` for the long name; a `szName`-only write cleared `szNameEx` on this reader. Freeze sets `nUserStatus=1` and leaves the user and face. Absence removes the user. Shadow mode logs the would-be write. When the per-reader flag is on, this path is the only writer and the old command dispatcher does not also write that reader.
4. **Frontend changes.** Per-reader projection lag replaces `deviceSyncState` on the member screen for flagged readers. The old panel remains for readers still on the outbox.
5. **Database changes.** Uses M2 tables. Record applied revision and last verification error per reader.
6. **API/message contract changes.** Add revision notification, bounded pull, and revision ack. Old `CREATE_USER` / `UPSERT_FACE` commands remain for readers whose flag is off. `SYNC_RESULT` with `skipped` is not an ack of a revision.
7. **Tests.** Server edit while the gateway is offline converges after reconnect and does not apply before the observation read. A newer revision makes the older one a no-op. A partial user payload is rejected by the gateway writer. Freeze projects `nUserStatus=1` and does not remove the user. Face `INSERT` over an existing photo is a failure. Ack without a successful read-back does not advance the cursor. One reader offline does not block another. State-transition tests from the architecture: server create, server edit offline, server disable, server remove-from-readers, stale revision.
8. **Migration considerations.** Turn the flag on for one non-production reader first, then the gym reader. While the flag is off, the old outbox still pushes, and the new path only logs.
9. **Rollback considerations.** Turn the flag off. The old outbox resumes for that reader. Do not delete the outbox in this milestone.
10. **Dependencies.** M1, M2, M3.
11. **Acceptance criteria.** On a flagged reader, server-only changes converge and verify. Stale revisions do not apply. Validity dates on the reader match the reader-local window that was projected. The old and new writers are never both active for the same reader.

## M5 — Observations and review

1. **Objective.** Reader-originated creates, edits, and disappearances become observations and review items. The gateway does not resolve them.
2. **Backend changes.** Ingest observations. Compare baseline, desired state, and observation. Equal states update the baseline. Server-only drift is applied by M4. Device-only drift becomes a review item or a pending enrollment. Both sides different and unequal becomes one conflict with both observations kept. Suggested matches may be shown from name and face hash. Nothing auto-links. A disappeared user does not delete or deactivate the member. `emAuthority` changes are stored on the observation and are not projected back out. Review actions are: accept server, accept device snapshot, link to an existing member, create a new member, restore to the reader, remove from all readers, reject a provisional enrollment. Each action writes an audit row.
3. **Gateway changes.** Detect by a full roster read, not by an edit alarm. On this firmware, validity, freeze, authority, face replace, and delete raised no alarm. A periodic safety scan is configurable and is not fixed at 15 seconds. The only measured roster is about 1,200 users in 3–5 seconds; do not schedule from an unmeasured larger roster. Face reads happen when the user fingerprint changes. A scan whose count does not match the announced total produces no deletions. An empty roster is a bad read, not a factory reset and not a mass delete. This unit cannot be factory-reset in place. A replaced reader is empty because it is a new device, and M4's projection fills it. Screen-created users keep the id the reader assigned. The POC's screen offered the smallest free number; the gateway must not overwrite that id or assume highest-plus-one. The same face may already exist on two ids; that is an observation, not a merge. While the backend is unreachable, the gateway may copy a new unlinked device record onto another reader so the door keeps working. That copy does not choose a member. Two readers that both changed are both reported. There is no local timestamp winner and no fan-out of a business decision. For a flagged reader, delete the live calls to `LocalMemberStore.Wins`, `FanOutAsync`, `AlignReaders`, `createFromDevice`, and `applyDeletion`.
4. **Frontend changes.** Review queue with server, each reader, and baseline side by side, including name, validity, freeze, face hash, `deviceUserId`, and presence. The actions listed above. Audit of who decided. Pending enrollment approval. This replaces the conflicts tab's DISMISS/REMOVE behavior for flagged readers.
5. **Database changes.** Uses M2 review and pending-enrollment tables. Observed snapshot updated only from a trusted list.
6. **API/message contract changes.** `DEVICE_USER_CHANGED` and reconciliation snapshots become observations. They are not applied as member edits. Old auto-import endpoints stay mounted only until M8 and are unused for flagged readers.
7. **Tests.** The architecture scenarios: reader creates a person offline; one reader edits while the other does not; both edit differently and no winner is chosen; reader delete keeps the member; duplicate event is idempotent; unexpected id collision does not overwrite; short user list creates no deletions; duplicate face on two ids creates no automatic link. Gateway tests fail if the flagged path calls fan-out or timestamp merge.
8. **Migration considerations.** Enable the flag only on readers already applying through M4. Open old `reconciliation_conflict` rows are copied into review items once, not resolved by the copy.
9. **Rollback considerations.** Flag off restores the old watcher for that reader. Review rows already created stay. They are not deleted on rollback, and the old watcher must not also apply them.
10. **Dependencies.** M4 for the same reader. M2 tables.
11. **Acceptance criteria.** A device edit is visible in review and is not applied to the member until a person chooses. A failed or short roster scan changes nothing. The gateway has no code path that picks a member winner.

## M6 — Bootstrap of the live gym

1. **Objective.** Map the readers that already have people onto members, and show every ambiguity instead of guessing.
2. **Backend changes.** A bootstrap report per reader: mapped and matching, mapped and different, unlinked device user, same device id used for different people on two readers, same person with different ids. Known mappings converge through M4. Unlinked people become pending enrollments. Collisions stay separate per-reader mappings. No master reader. No import that joins on `deviceUserId` across the tenant.
3. **Gateway changes.** One full trusted read per reader, then the normal M5 observation upload. No `AlignReaders` copy from a "full" reader to an "empty" one during bootstrap.
4. **Frontend changes.** The bootstrap report and the M5 review queue. Staff link, create, or reject. No bulk auto-link button.
5. **Database changes.** Bootstrap run id on the review and enrollment rows so the report can be repeated without duplicating decisions.
6. **API/message contract changes.** Replace `POST /devices/{id}/import-users` for this flow. The old route remains until M8 and is not called by the new UI.
7. **Tests.** Each bootstrap case in the architecture. A missing mapping never creates a member. Re-running the report does not duplicate a resolved item.
8. **Migration considerations.** Run against the live roster only after M5 is on for that reader. Existing serial-based device ids remain the mapping values.
9. **Rollback considerations.** Stop the bootstrap job. Resolved items stay audited. Unresolved items remain in the queue and the old import is not switched back on for a reader that has already been flagged.
10. **Dependencies.** M5.
11. **Acceptance criteria.** Every unlinked or conflicting device user is visible. Every already-correct mapping converges without a review item. No reader user id is rewritten.

## M7 — Attendance, isolated

1. **Objective.** Buffer and store the punches the log already holds, without affecting member sync, and without behavior the POC did not show.
2. **Backend changes.** Append-only ingest. Resolve the member only through `(deviceId, deviceUserId)` mapping. Keep the raw device user id when no mapping exists. Do not rewrite `member_id` on older rows. Do not treat a missing entry/exit pair as a sync error. Do not build visit-pairing reports in this milestone.
3. **Gateway changes.** Poll by a bounded time window. There is no `afterRecNo` query on this SDK. Do not ingest punches from `ALARM_ACCESS_CTL_EVENT`; eight walks stored a punch and none raised that event. Interpret `stuTime` as UTC. The reader clock itself stays India local and is not changed. Store record number, device user id, UTC timestamp, method, granted/denied, and the error code the log returned (`0xA4` for freeze, `0x14` for not-yet-valid were the codes this run saw). Idempotency is `(deviceId, recNo, stored timestamp)`. Record number alone is not the key: survival across reboot and a full log was not shown. A re-read of the same punch matches. A later reuse of a record number at a different time is a different row. The attendance cursor is the last window confirmed by the server. A failed query is an error, not an empty log. This work runs on its own queue and must not block the member worker. The measured history on this unit includes record 1 from 2025-10-12 and a reported count of about 124,000. Do not code a retention limit or a "log is full" rule.
4. **Frontend changes.** None beyond existing attendance views reading the append-only rows. No new report UX.
5. **Database changes.** Stop mutating `attendance_event.member_id`. Add the stored record number and UTC timestamp. Uniqueness is `(device_id, rec_no, event_time)`, not `rec_no` alone.
6. **API/message contract changes.** Attendance messages come from the poller. `DEVICE_EVENT` is not an attendance source. Member messages stay on their own channel.
7. **Tests.** The same punch delivered twice stores one row. A punch with no mapping is stored and not dropped. A failed reader query does not wipe the cursor. Member apply continues while an attendance window is retrying. A freeze denial and a not-yet-valid denial are stored as punches, not as member edits.
8. **Migration considerations.** Start the cursor at the time of deploy, not at year 2000, so the first run does not pull the whole 124,000-row history. A later backfill window can be requested explicitly. Old fingerprint rows stay. New rows use the new key.
9. **Rollback considerations.** Stop the poller. Existing attendance rows stay. Member sync does not depend on this milestone.
10. **Dependencies.** M3 for the local cursor. M2 mapping for member resolution. It does not depend on M4 or M5 to store raw punches, and it must not block them.
11. **Acceptance criteria.** Punches survive a gateway restart exactly once. Member convergence does not wait on attendance. No code queries the reader for "records after N." No code claims how long the reader keeps a full disk.

## M8 — Cutover

1. **Objective.** Remove the old sync architecture after flagged readers have matched the new path.
2. **Backend changes.** Delete the per-command outbox as the live path, timestamp-winner application, serial fallback, auto-import, and `markHeldBySource`. Drop `membership.device_sync_state` and the unused `*_changed_at` columns after the UI no longer reads them. Do not carry forward `CLEAR_DEVICE_LOGS`.
3. **Gateway changes.** Delete fan-out, `AlignReaders`, `PlausibleDeviceTime`, the JSON member store, and the command applier. The journal and the per-reader worker remain.
4. **Frontend changes.** Remove the old sync panel, the "newer copy wins" action, and the conflicts tab that only dismisses or removes. The review queue is the screen.
5. **Database changes.** Freeze `device_sync_command`, then drop it after the rollback window. Migrate any still-open old conflicts into review items first. Drop `reconciliation_conflict` after that.
6. **API/message contract changes.** Remove the per-operation command push and the unauthenticated message fallback. Revision pull, observation, and attendance poll are the contract.
7. **Tests.** The M4–M7 suites pass without the old services on the classpath. A test that instantiates `DeviceSyncService` or `LocalMemberStore.Wins` fails the build.
8. **Migration considerations.** Cut over only when every gym reader has been flagged, shadow comparisons show the same verified projection, and the review queue has no unresolved identity ambiguity that bootstrap already knows about.
9. **Rollback considerations.** Until the drop migration runs, the flag can return a reader to the old outbox. After the drop, rollback is a restore of the database backup and the previous build. Do not drop the outbox tables in the same release that flips the last reader.
10. **Dependencies.** M4, M5, and M6 on every live reader. M7 can ship before or after this cutover; it is not a reason to keep the old member path.
11. **Acceptance criteria.** The production path has one writer per reader, one observation path, and no timestamp or `deviceUserId` identity rule left in code.

## Still a human decision, not a milestone

These do not block the sequence above:

- Whether a later release narrows which members go on which reader. The projection table already allows that.
- Who may approve review items, and whether a pending enrollment expires.
- Whether the authenticated HTTP poll remains beside WebSocket. M1 makes either path authenticated; M8 can remove the poll if you choose WebSocket only.
- Whether M7 ships in the first release. Member sync does not wait for it.
- How long the M8 rollback window stays open.
- A spare-reader test of a full log and of record numbers across power loss, if a later attendance cursor needs it. It is not part of this plan.
