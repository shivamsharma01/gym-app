# Execution plan

Status: revised implementation sequence. No application code is authorized by this document.
Approved architecture: [gym-device-sync-before-poc-architecture.md](gym-device-sync-before-poc-architecture.md).
Approved migration plan: [migration-plan.md](migration-plan.md).
Evidence: [device-poc-results.md](device-poc-results.md), reader serial `TW30000005250265`.

This sequence replaces the earlier V1–V17 order. The migration plan's M1–M8 milestones stay the management view. Offline copying of an unlinked device user onto another reader is not in this release. The architecture allows that copy as an optional continuity step. Doing it would make the gateway choose where an unidentified person exists while the server is down. It waits for an explicit product decision.

## Rules

- The backend is the only business reconciliation authority. The gateway does not choose winners.
- `publicId` is member identity. `deviceUserId` is a device-local mapping value.
- Device-originated changes are stored and shown. They are not dropped and they are not auto-merged.
- Revisions and the baseline order changes. Timestamps do not.
- Desired state is verified on the reader before it is acknowledged.
- A stale revision does not move a reader backward.
- A flagged reader has one member writer.
- Unverified hardware stays out: live door events as attendance, queries after a record number, SDK auto-reconnect as a rule, in-place factory reset, `emAuthority` as menu permission, and record-number survival across power loss or a full log.

## How a server-created deviceUserId is chosen

This rule is for a member the server creates. It is not used for a person the reader screen creates. Those ids stay exactly as the reader assigned them.

`publicId` is not written to the reader. It is a UUID. The SDK's face user-id buffer is `NET_STRING_32_USER_ID`. A hyphenated UUID does not fit that buffer. This reader's screen and the probe only demonstrated decimal ids (`1210`, `1211`, `990001`). A non-numeric id is not verified.

For one reader:

1. Load every device user id already mapped to that reader, every id in its last trusted observation, and every id already sitting on an unacked desired projection for that reader.
2. Let H be the largest integer in that set. If none of the ids are integers, H is 0.
3. The candidate is the decimal text of H + 1. If that value is already in the set, increment until it is free.
4. Store it only on `member_device_mapping` for that member and that reader. Do not derive it from `publicId`, member code, or serial number.
5. The gateway writes that id. If the reader already has it, the gateway does not overwrite and does not pick another id. It reports the collision. The backend allocates the next integer, updates the mapping, and retries.

The screen fills the smallest free integer (P2). Highest-known-plus-one stays away from those holes while any lower id is free. When the roster is dense, both algorithms want the same next integer. The collision retry above is the protection. It was not run as a live race on the hardware. The probe did show that the SDK accepts a numeric id the screen did not choose: test user `990001` was created while the screen later offered `1210`.

## A. Foundation sequence

### F1 — Authenticated channel

A gym has one gateway. Its connection requires that gateway's credential. The gateway id comes from the credential. Expired tokens fail. The gym's gateway cannot speak for another gym's reader. Anonymous access is removed, with no flag to turn it back on. This is M1. No desired-state message exists until this is true.

### F2 — Fake reader

The first hardware-independent test seam. A scripted adapter implements list-with-announced-total, get user, create full user, get/update/insert/remove face, and a time-window punch query. It can return `PHOTO_EXIST`, `NO_RECORD`, fail code `UNKNOWN` with SDK error `0x800004B5`, a short list, an empty list, an occupied id, and a failed call. It does not decide membership or allocate member ids.

### F3 — Gateway execution, minimum for the first slice

Only what the first member-create slice needs:

- SQLite file for that gateway.
- One worker for one reader.
- Durable outbound journal for the ack of a verified revision, not for heartbeats.
- Applied revision for that reader.
- Retry metadata: attempt count, next attempt, last error.
- Restart loads the journal and does not apply an already verified revision twice.
- Device calls go through the adapter boundary. Tests use F2. The TrueFace adapter is not required for the slice to pass.

Not in F3: attendance cursor, provisional-enrollment store, snapshot history, a second reader worker, scan scheduling, or any business comparison.

## B. Vertical slices

### V1 — Server creates a member on one reader

Specified in full in section C. This is the first vertical slice.

### V2 — Freeze and enable

**Behavior.** Staff disallow access. The same device user remains, with `nUserStatus=1` and the same face. Staff allow access again and the status reads back `0`. The member is not deleted.

**Prerequisites.** V1.

**Backend.** Access change writes a new desired revision for that reader. Freeze is `nUserStatus=1`. The mapping and `publicId` stay.

**Gateway.** Full-record write of the existing id, including validity, then read-back, then ack. No ack on a mismatched status.

**Adapter.** Fake reader keeps the face across the user write.

**Frontend.** The existing disallow control publishes the revision for a flagged reader.

**Database.** Frozen flag distinct from archived. Desired projection carries the status.

**Tests.** Disallow, then allow. Face hash unchanged. Member row remains. A wrong read-back does not ack.

**Failure scenarios.** Write ok, read-back still enabled: revision stays behind and retries.

**Acceptance criteria.** Reader status matches the server flag. Face and member id are unchanged.

**Coexistence.** Old disable-by-delete must not run. One writer.

**Done.** Tests green on the fake reader.

### V3 — Name and validity replace the whole user record

**Behavior.** Staff change the name and the membership dates. The reader stores the long name and the reader-local dates. A payload without validity never reaches the adapter.

**Prerequisites.** V1.

**Backend.** Desired record includes `szNameEx` and validity. End of the reader's local day is `23:59:59`. Dates are not converted to UTC before the write.

**Gateway.** Refuse a user write that omits validity.

**Adapter.** If a test bypasses the guard, the fake reader zeros validity, matching P3. The real path does not send that payload.

**Frontend.** Member edit on a flagged reader publishes a revision.

**Database.** No new tables.

**Tests.** Round-trip of name and dates. Partial payload makes zero adapter calls.

**Failure scenarios.** Read-back dates differ: no ack.

**Acceptance criteria.** Read-back equals the desired name and validity. `SynchronizeTime` is not called.

**Coexistence.** Same single-writer rule.

**Done.** Tests green.

### V4 — Face replace

**Behavior.** Staff replace the photo. The reader ends with those bytes. A second `INSERT` over an existing photo fails with `PHOTO_EXIST` and is not acked. An `UPDATE` with no photo does not clear the photo.

**Prerequisites.** V1.

**Backend.** Face is its own revision data. The comparison hash is the hash read back from the reader.

**Gateway.** `UPDATE`, or remove then `INSERT`. Empty `UPDATE` is not a delete.

**Adapter.** Scripts `PHOTO_EXIST` and a re-encoded read-back.

**Frontend.** Photo field publishes a face revision.

**Database.** Face reference on the desired projection. Read-back hash on the observation.

**Tests.** Success hash equals read-back. `PHOTO_EXIST` does not ack. Empty update keeps the previous bytes.

**Failure scenarios.** User revision applied, face revision failed: the projection is not claimed complete.

**Acceptance criteria.** Get-face returns the new bytes only after ack.

**Coexistence.** Old `UPSERT_FACE` does not run for this reader.

**Done.** Three tests green.

### V5 — Remove from one reader

**Behavior.** Staff remove the member from this reader. The device user is gone. The member remains. No other reader is written.

**Prerequisites.** V1.

**Backend.** Desired presence false. Member not archived.

**Gateway.** Remove, then get-user must be `NO_RECORD`. SDK error `0x800004B5` alone is not that result. Fail code `UNKNOWN` on a get-face is a missing photo, not a missing user.

**Adapter.** Scripts both fail codes.

**Frontend.** Per-reader remove, separate from deactivate.

**Database.** Presence on the desired projection.

**Tests.** Reader A loses the user. Reader B is not called. Member remains. A second remove is idempotent.

**Failure scenarios.** Remove reports ok but the user is still there: no ack.

**Acceptance criteria.** Verified absence on A only.

**Coexistence.** Delete-member-everywhere does not run.

**Done.** Tests green.

### V6 — Reconnect and stale revisions

**Behavior.** A member change happens while the gateway is offline. Reconnect reads the reader before writing. An older revision after a newer one does not write.

**Prerequisites.** V1.

**Backend.** Pull is "after revision R."

**Gateway.** Order: authenticate, reader answers, read, pull, apply, verify, ack. Restart uses F3 and does not apply a verified revision again.

**Adapter.** Call log shows the first call after reconnect is a read.

**Frontend.** None.

**Database.** F3 cursor.

**Tests.** Offline create or edit converges. Revision 4 then 3 leaves the reader on 4. Kill and restart does not double-write.

**Failure scenarios.** Reader silent: nothing is applied. The slice does not require SDK auto-reconnect.

**Acceptance criteria.** Call order and stale-revision tests green.

**Coexistence.** The old outbox does not replay commands for this reader on that reconnect.

**Done.** Tests green, including one restart.

### V7 — The other reader keeps going

**Behavior.** Two readers have pending revisions. One adapter fails. The other applies and acks.

**Prerequisites.** V6. F3 grows from one worker to a second worker here, not before V1.

**Backend.** Independent revisions.

**Gateway.** Worker failure is isolated.

**Adapter.** Two fake readers.

**Frontend.** None.

**Database.** Per-reader cursor and last error.

**Tests.** B acks while A remains on the old revision. A later success on A does not rewrite B.

**Failure scenarios.** A fails for the whole test.

**Acceptance criteria.** B's applied revision matches its desired revision first.

**Coexistence.** The old single watcher loop is not the executor for these flagged readers.

**Done.** Test green.

### V8 — A person created on the reader waits for review

**Behavior.** The reader has a new id the server did not allocate. A trusted scan stores a pending enrollment. No member is created. The device id is not changed. The person stays only on that reader while the server is unreachable. Nothing is copied to another reader.

**Prerequisites.** V6.

**Backend.** Observation in, enrollment out, member count unchanged.

**Gateway.** Upload only when the list count equals the announced total. Do not allocate an id. Do not copy the user anywhere.

**Adapter.** One new id. Announced total matches.

**Frontend.** None until V13.

**Database.** Observed snapshot and pending enrollment.

**Tests.** One enrollment, zero new members. A second scan does not duplicate it. During a backend outage the fake sibling reader receives no write.

**Failure scenarios.** Duplicate observation is idempotent.

**Acceptance criteria.** The stored id equals the reader's id. The sibling adapter's write count stays zero.

**Coexistence.** `createFromDevice`, `importUsers`, `AlignReaders`, and `FanOutAsync` do not run.

**Done.** Tests green.

### V9 — A reader edit becomes a review item

**Behavior.** A mapped user's name on the reader differs from the desired record. The member is not overwritten. One review item holds server, reader, and baseline. An `emAuthority` difference is stored and not pushed back.

**Prerequisites.** V8.

**Backend.** Three-way compare. No timestamp comparison.

**Gateway.** Upload only. No local merge.

**Adapter.** One mapped user with a different name.

**Frontend.** None until V13.

**Database.** Review item.

**Tests.** Canonical name unchanged. One item. Repeat scan does not open another.

**Failure scenarios.** Duplicate observation still one item.

**Acceptance criteria.** The member row matches the baseline.

**Coexistence.** `DeviceUserChangeService.apply` and timestamp merge do not consume the observation.

**Done.** Tests green.

### V10 — Two readers disagree

**Behavior.** The same member differs on two readers. Both observations are kept. The member stays at the baseline. Neither reader is overwritten from the other.

**Prerequisites.** V9.

**Backend.** One conflict, two observations.

**Gateway.** Send both. No cross-write.

**Adapter.** Two different names.

**Frontend.** None until V13.

**Database.** One review item, two snapshots.

**Tests.** Canonical name unchanged. A late second observation joins the same conflict.

**Failure scenarios.** The second observation arrives after the first is stored.

**Acceptance criteria.** Both values still present. No adapter write from one reader to the other.

**Coexistence.** Fan-out and latest-wins do not run.

**Done.** Tests green.

### V11 — A trusted disappearance keeps the member

**Behavior.** A mapped id is missing from a list whose count matches the announced total. The member stays. One review item records the absence. No other reader is changed.

**Prerequisites.** V9.

**Backend.** Do not deactivate the member.

**Gateway.** Emit absence only for a trusted list.

**Adapter.** Matching announced total, one mapped id gone.

**Frontend.** None until V13.

**Database.** Device-missing review item.

**Tests.** Member flags unchanged. One item. Second scan does not duplicate it.

**Failure scenarios.** Repeated scan.

**Acceptance criteria.** No remove command is sent to any reader by this scan.

**Coexistence.** `applyDeletion` and `DetectDeletions` do not run.

**Done.** Tests green.

### V12 — A bad list changes nothing

**Behavior.** A short list, an empty list, or a count that misses the announced total creates no disappearance and no member change. Empty is a bad read. This reader cannot be factory-reset in place.

**Prerequisites.** V11.

**Backend.** Reject the set.

**Gateway.** Compare the count before emitting removals.

**Adapter.** Three bad scripts, then one good list.

**Frontend.** None.

**Database.** No new review rows from the bad scripts.

**Tests.** One assertion per bad list. The following trusted list still works.

**Failure scenarios.** A bad list between two good scans.

**Acceptance criteria.** Zero review items from the bad scans.

**Coexistence.** The current deletion detector does not see the list.

**Done.** Tests green.

### V13 — The same face on two ids is not one member

**Behavior.** Two device users read back the same face. Both observations remain. No mapping is created from the hash.

**Prerequisites.** V8.

**Backend.** Store both. Do not link.

**Gateway.** Upload both. Do not deduplicate.

**Adapter.** Identical face bytes, two ids.

**Frontend.** None.

**Database.** Two observed hashes.

**Tests.** No new mapping. A later scan does not collapse the pair.

**Failure scenarios.** Third scan.

**Acceptance criteria.** Two device ids remain. Member count is unchanged by the hash match.

**Coexistence.** Face-hash auto-link does not run.

**Done.** Tests green.

### V14 — A staff decision is verified on the reader

**Behavior.** Staff link, create a member, accept the server value, or reject the enrollment. The decision becomes a desired revision. The gateway applies it and acks only after read-back. Link and create keep the reader's existing id. Reject creates no member and removes that device user. Accept-server writes the server value. Restore uses V2's writer. Remove-from-readers uses V5.

**Prerequisites.** V1, V8, V9.

**Backend.** Audit actor, prior state, chosen state, and revision. Mapping uses `publicId` plus the existing device id.

**Gateway.** V1's apply path only. No new decision code.

**Adapter.** Read-back matches the choice.

**Frontend.** Review screen: server, reader, baseline, and those actions. No auto-link and no bulk link.

**Database.** Audit on the review row.

**Tests.** Each action. Create does not change the device id. A failed apply leaves the item open with the verification error.

**Failure scenarios.** Decision saved, reader write fails.

**Acceptance criteria.** Reader, member, and mapping match the choice, and an audit row exists.

**Coexistence.** The old DISMISS/REMOVE conflict actions do not close these items.

**Done.** Action tests green, and one action is exercised through the UI test.

### V15 — Bootstrap

**Behavior.** One scripted roster contains a correct mapping, a mapped user who differs, an unlinked person, and a device id that is a different person on another reader. The correct mapping converges through V1. The other three become review or enrollment rows. No id is rewritten. Re-running the report does not duplicate a resolved item.

**Prerequisites.** V14.

**Backend.** Bootstrap report with a run id.

**Gateway.** One trusted read, then the V8 upload. No copy from a full reader onto an empty one.

**Adapter.** Scripted roster, not the live gym.

**Frontend.** The report. Actions go through V14.

**Database.** Run id on the items.

**Tests.** One assertion per architecture bootstrap case. A short list aborts and creates nothing.

**Failure scenarios.** V12 during the report.

**Acceptance criteria.** Known mappings converge. Ambiguous people stay unresolved. No device id changes.

**Coexistence.** `POST /devices/{id}/import-users` is not called.

**Done.** Tests green.

### V16 — One writer

**Behavior.** The flag makes the new worker the only member writer for that reader. Flag off is the rollback and does not delete review rows. Dropping the old outbox is a later release, after every live reader is flagged and V15 left no unresolved identity.

**Prerequisites.** V1 through V15 for that reader.

**Backend.** Flag checked on dispatch and on ingest.

**Gateway.** Spy fails the test if the old dispatcher and the new worker both call the adapter.

**Adapter.** The spy.

**Frontend.** Old sync panel hidden for a flagged reader.

**Database.** `device_sync_command` is not dropped in the release that flips the flag.

**Tests.** Spy. Flag off does not delete review rows. A call to timestamp-wins fails the build for the flagged path.

**Failure scenarios.** Flag on during a bad scan: V12 still holds, and the old deletion detector does not run as a fallback.

**Acceptance criteria.** One writer. Unflagged readers still use the old path.

**Coexistence.** This slice is that rule.

**Done.** Spy test green. The gym reader stays unflagged until section E.

### Deferred — attendance

Not on the critical path. After V1 through V15 are stable, a later slice can poll one time window, store punches append-only, treat `stuTime` as UTC, and key rows by device id plus record number plus stored timestamp. It must not ingest `ALARM_ACCESS_CTL_EVENT`, must not query after a record number, and must not block the member worker. That work is M7. It is not scheduled here.

### Deferred — offline copy to another reader

Not in this release. An unlinked person remains on the originating reader through an outage and is reconciled when the server is back (V8). Copying that person to a sibling reader requires a later product decision. Until then the gateway has no such path.

## C. First vertical slice

**Slice ID.** V1.

**Name.** Server creates a member on one reader.

**Behavior.** Staff create a member who has a face. The server saves the canonical member, allocates one device-local id, writes the mapping and the desired projection, and the gateway creates that user and face on one reader, reads them back, and acknowledges. The applied revision then equals the desired revision.

**Prerequisites.** F1, F2, F3.

**Steps.**

1. Create the member. Identity is a new `publicId`.
2. Allocate `deviceUserId` by the rule in this document. Do not use `publicId`.
3. Insert `member_device_mapping` for this one reader.
4. In that same transaction, write desired presence, the full user fields, and the face reference, and increment the reader revision.
5. Notify the gateway with the reader id and the revision only.
6. The worker pulls desired state after its applied revision.
7. It creates the user with the full record the probe already used for a new user: the allocated id, name, `szNameEx` when the name needs it, authority `Customer`, `nUserStatus=0`, validity begin and end on the reader-local clock, and the door and time-section counts that create used (`nDoorNum=1`, `nTimeSectionNum=1`). It does not send `Administrators`.
8. It inserts the face. This user has no photo yet, so the call is `INSERT`, not a second insert and not an empty update.
9. It reads the user and the face back. The id, name, validity, status, and face hash must match. The hash is of the bytes read back.
10. Only then does it acknowledge. The backend marks that revision applied.
11. A restart after the ack does not create the user again.

**Backend.** Member, mapping, desired projection, revision notification, paged pull, ack. Reject an ack that does not include a matching read-back.

**Gateway.** F3 worker performs the steps above. It does not compare timestamps. It does not allocate a replacement id.

**Adapter.** F2. One extra test points the same worker at a fake that already has the candidate id. The gateway reports occupancy and writes nothing. The backend allocates the next integer and the retry then succeeds.

**Frontend.** The existing create-member action, pointed at one selected reader for this slice. No reader-picker product beyond that one reader.

**Database.** Member with `publicId`. Mapping unique on `(device, deviceUserId)` and `(device, member)`. Desired projection and applied revision. F3 SQLite cursor. No attendance tables.

**Tests.** Happy path from create through applied revision. Occupied-id retry without overwrite. Ack withheld when the face hash differs. Restart does not duplicate the user. `publicId` does not appear in any adapter write.

**Failure scenarios.** User create ok, face insert fails: user revision is not fully applied and the face retries. Read-back mismatch: no ack. Occupied id: no overwrite.

**Acceptance criteria.** After success, the fake reader has exactly the allocated id, the full user fields, and the face bytes. The mapping points `publicId` at that id. Applied revision equals desired revision. The member is not looked up by device id.

**Coexistence.** The old outbox must not also create this user. The test reader is flagged. Other readers may stay on the old path.

**Done.** The tests are green on F2, and the adapter log shows one user create and one face insert for the successful revision.

## D. Remaining gaps

These are not defined tightly enough to pretend they are already specified.

| Gap | Where it stands |
| --- | --- |
| Device-id allocator | The architecture left the exact allocator open. This plan defines highest-known-plus-one decimal ids, plus no-overwrite retry. A live race against the screen was not run. |
| `publicId` as `szUserID` | Not supported here. UUID form was never written to this reader, and the face user-id type is a 32-character buffer. |
| Which readers receive a new member | V1 is one reader chosen at create time. The long-term rule (every reader, by branch, or per member) is still a product choice. |
| Who may approve review items, and whether an enrollment expires | Not specified. V14 cannot invent a role or an SLA. |
| Safety-scan interval | Not benchmarked. The only measurement is about 1,200 users in 3–5 seconds. Do not hardcode 15 seconds, and do not schedule from an unmeasured larger roster. |
| Offline copy to a second reader | In the architecture as optional continuity. Excluded from this release on purpose. |
| Create without a face | V1's proof includes a face. A member with no photo is not specified as success for V1. |
| Door and time-section fields beyond the probe's create | V1 sends the same `nDoorNum=1` and `nTimeSectionNum=1` the probe used. Other schedules are not verified. |
| SDK reconnect | One power cycle reconnected and one did not. V6 re-reads and pulls. It does not depend on the old login surviving. |
| Attendance cursor across reboot or a full log | Unproven. Attendance is deferred, so this does not block V1. |
| `emAuthority` | The menu asks for the reader password. V1 sends `Customer`. Authority changes are observations later, not a projection. |

## E. Physical-reader confirmation

Required once, on a reader, before that reader is flagged in V16. The fake adapter is not a substitute for this pass.

- V1 create: the allocated numeric id, full user read-back, and face read-back.
- V2 freeze and enable.
- V3 dates stored as sent.
- V4 face replace and `PHOTO_EXIST`.
- V6 reconnect read-before-write.

V8 through V15 do not need a new hardware experiment. Do not factory-reset this unit and do not fill its log to test them.

## F. Fake reader only

F1's credential tests, F2, F3, and V1 through V16 can be implemented and accepted on the fake reader and the test database. No physical reader is required to write or merge those slices.

## G. Manual review gates

Before any application code:

1. This execution plan, including the device-id rule and the exclusion of offline cross-reader copy.

Before the next slice during implementation:

2. After F1, before any desired-state message is added.
3. After F3, before V1, confirming the SQLite journal still contains no business decision.
4. After V1 is green on the fake reader, before V2.
5. After V8, confirming a sibling reader received no write during an outage.
6. After V14, before V15 uses a roster shaped like the gym.
7. After the section E hardware pass, before V16 flags the gym reader.
8. Before the later release that drops the old outbox.
