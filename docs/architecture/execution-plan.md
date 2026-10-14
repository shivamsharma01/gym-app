# Execution plan

Status: F1–F3 and V1–V21 are implemented. Their automated tests passed on the fake reader and the test database. Section E has not been run on a physical reader.
Architecture: [device-sync-architecture.md](device-sync-architecture.md).
Open questions: [open-questions.md](open-questions.md).
Evidence: [device-poc-results.md](device-poc-results.md), reader serial `TW30000005250265`.

Offline copying of an unlinked device user onto another reader is not in this release. That copy would make the gateway choose where an unidentified person exists while the server is down. It waits for an explicit product decision.

## Rules

- The backend is the only business reconciliation authority. The gateway does not choose winners.
- `publicId` is member identity. `deviceUserId` is a device-local mapping value.
- Device-originated changes are stored and shown. They are not dropped and they are not auto-merged.
- Revisions and the baseline order changes. Timestamps do not.
- Desired state is verified on the reader before it is acknowledged.
- A stale revision does not move a reader backward.
- Each reader has one member writer: its desired-state worker.
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

A gym has one gateway. Its connection requires that gateway's credential. The gateway id comes from the credential. Expired tokens fail. The gym's gateway cannot speak for another gym's reader. A connection without that credential is refused. No desired-state message exists until this is true.

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

**Frontend.** The existing disallow control publishes a desired revision for that reader.

**Database.** Frozen flag distinct from archived. Desired projection carries the status.

**Tests.** Disallow, then allow. Face hash unchanged. Member row remains. A wrong read-back does not ack.

**Failure scenarios.** Write ok, read-back still enabled: revision stays behind and retries.

**Acceptance criteria.** Reader status matches the server flag. Face and member id are unchanged.

**Member writer.** The desired-state worker is the only component that changes this reader's user record.

**Done.** Tests green on the fake reader.

### V3 — Name and validity replace the whole user record

**Behavior.** Staff change the name and the membership dates. The reader stores the long name and the reader-local dates. A payload without validity never reaches the adapter.

**Prerequisites.** V1.

**Backend.** Desired record includes `szNameEx` and validity. End of the reader's local day is `23:59:59`. Dates are not converted to UTC before the write.

**Gateway.** Refuse a user write that omits validity.

**Adapter.** If a test bypasses the guard, the fake reader zeros validity, matching P3. The real path does not send that payload.

**Frontend.** Member edit publishes a desired revision for that reader.

**Database.** No new tables.

**Tests.** Round-trip of name and dates. Partial payload makes zero adapter calls.

**Failure scenarios.** Read-back dates differ: no ack.

**Acceptance criteria.** Read-back equals the desired name and validity. `SynchronizeTime` is not called.

**Member writer.** The desired-state worker is the only component that changes this reader's user record.

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

**Member writer.** Face bytes are written only as part of the desired revision for this reader.

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

**Member writer.** Removal is a desired absence on this reader. The member stays, and no other reader is written.

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

**Member writer.** Reconnect applies the current desired revisions for this reader after the observation. A stale revision is not applied.

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

**Member writer.** Each reader has its own worker. One reader's failure does not write the other reader.

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

**Member writer.** The observation is stored. No member is created, and no other reader is written.

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

**Member writer.** The observation becomes a review item. The member row is not changed from the reader clock.

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

**Member writer.** Neither reader is overwritten from the other.

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

**Member writer.** The absence is a review item. The member stays, and no reader is told to delete anyone because of this scan.

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

**Member writer.** An untrusted list creates no desired absence and no member change.

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

**Member writer.** Equal face bytes do not create a mapping or a member.

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

**Member writer.** The decision becomes a desired revision. The review item stays open until that revision is verified and acknowledged.

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

**Member writer.** The report classifies the roster. It does not create members by joining device user ids across readers, and it does not copy one reader's people onto another.

**Done.** Tests green.

### V16 — One writer

**Behavior.** Each reader has one member writer: the desired-state worker for that reader. Member create, update, freeze, enable, face change, and removal are desired revisions. A bad or short scan does not remove mapped users. A review item stays until the corresponding device change is verified and acknowledged.

**Prerequisites.** V1 through V15.

**Backend.** Member changes publish desired revisions. A roster ingest stores observations and review or enrollment rows. It does not pick a winner by clock, and a short list does not delete a member.

**Gateway.** The reader worker is the only caller that writes member records on that reader. A spy fails the test if another component writes the same adapter during a member change.

**Adapter.** The spy.

**Frontend.** Staff change a reader through desired state. Reader-originated differences are on the review queue. Staff do not get an action that overwrites a reader because one clock is later.

**Database.** Desired projections, review items, pending enrollments, and acknowledgements stay in the database. A bad release is rolled back with the previous build and a database backup. That backup is the recovery.

**Tests.** Spy shows a single writer. A short scan creates no disappearance. Review rows remain until a verified decision. The member path does not call a clock-wins comparison.

**Failure scenarios.** A bad scan while the worker is active: V12 still holds. No member is removed on this reader or any other because the list was short.

**Acceptance criteria.** One writer per reader.

**Done.** Spy test green. Section E has not been run.

### V17 — Staff decide review items

**Behavior.** Gym admin and staff can decide a review item or a pending enrollment: accept server, restore, remove, link, create, and reject. Staff do not gain device, gateway, or bootstrap management.

**Prerequisites.** V14.

**Backend.** A `REVIEW_DECIDE` permission. The decision endpoints require it. `GET /api/v1/reviews` stays on `DEVICE_VIEW`, and `POST /api/v1/reviews/bootstrap` stays on `DEVICE_MANAGE`. The decision path does not change: it still publishes a desired revision, and only the read-back acknowledgement closes the item.

**Gateway.** No change.

**Adapter.** No change.

**Frontend.** Decision buttons appear for `REVIEW_DECIDE`. The bootstrap button still needs `DEVICE_MANAGE`.

**Database.** The V2 seed adds the permission. Gym admin and super admin get it through the existing grant of every permission. Staff get it explicitly.

**Tests.** The seed catalogue still matches the enum. Staff can accept the server value on a review item. Staff can link a pending enrollment to an existing member, and can create a member from a pending enrollment; each keeps the reader's `deviceUserId`, writes only that reader, and creates no duplicate member. A failed read-back leaves the enrollment open with the error and does not advance the applied revision. Each committed decision writes one `REVIEW_DECIDED` or `ENROLLMENT_DECIDED` audit row with the actor, reader, device user id, member, and revision; create also keeps its `MEMBER_CREATED` row. A refused, conflicting, or rolled-back decision writes no decision audit row. Staff get 403 on bootstrap and on device create. Report viewer gets 403 on a decision.

**Acceptance criteria.** A staff decision is audited with the staff username and closes only after the acknowledgement.

**Done.** Automated tests passed on the fake reader and test database: `RbacSeedIT`, the three staff tests in `StaffReviewDecisionIT`, and the staff case in `e2e/review.spec.ts`. No physical-reader check applies beyond Section E.

### V18 — A new member goes to every reader of the gym

**Behavior.** Creating a member with a face publishes one desired user to every active reader of the gym. Each reader gets its own `deviceUserId` from the allocator, its own mapping, and its own revision. A reader that is offline converges when it returns (V6). One reader failing does not undo the member or the other readers.

**Prerequisites.** V1, V7.

**Backend.** Create no longer takes a reader id. It allocates per reader in one transaction, taking each reader's revision lock in ascending device id order so two fan-outs cannot deadlock. A reader added later gets the existing active members through the same desired path; no copy is made from another reader. Do not add a constructor parameter beyond the Sonar limit; extend the existing create service instead.

**Gateway.** No change. Each reader worker applies its own revision.

**Adapter.** No change.

**Frontend.** The create form drops the reader picker. The member page shows each reader's applied or pending state.

**Database.** No new table.

**Tests.** Two readers: both get the user and face, with independent ids. An occupied id on one reader retries there only. An offline reader converges after reconnect. A gym with no reader still saves the member and publishes nothing.

**Acceptance criteria.** Every active reader's applied revision reaches its desired revision for the new member.

**Done.** Automated tests passed on the fake reader and test database: `MemberCreateOnEveryReaderIT` (two readers with independent ids and the same face, an occupied id retried on one reader only, an offline reader converging after reconnect, one reader's failed read-back leaving the member and the other reader applied, a gym with no reader publishing nothing, no create without a face, and the per-reader applied or pending state); `ReaderConcurrencyIT.concurrentCreatesFanOutToEveryReaderWithoutDeadlockAlongsideAReverseOrderRename`; and the create form and member page cases in `e2e/one-writer.spec.ts`. An "active reader" is a reader of the gym with a gateway; there is no separate active flag. The server seeds a reader added later with the existing active members once it receives a trusted roster, empty or not: each active member with a face that has no mapping on that reader is written through the desired-state writer and the reader's allocator, which now also treats every id seen on a reader list as taken. Existing mappings, staff removals, and unknown people stay as they are; nobody is linked. A later trusted roster writes only what is still missing. The roster is recorded under the reader lock, so it cannot deadlock with a create on the same reader. A trusted roster no longer reports a member as missing when the reader never showed or verified that id, so a member published before the reader's first roster does not open a review item. `ReaderAddedLaterSeedingIT` covers members created before the first roster, a partially mapped reader, a non-empty roster with unknown users, and rosters racing creates; `ReaderConcurrencyIT` and `ReaderAddedLaterSeedingIT` passed in 5 repeated runs. These tests post the roster to the server directly. The gateway today sends a complete list as one message only when the reader is empty; a non-empty reader's users arrive one at a time, and those do not trigger the seed. So on the live path, a reader added with people already on it does not yet receive the existing members, and a member who becomes active or gets a photo later does not reach a reader that lacks them. Both are V21, now built. The membership row on the member page no longer shows the old device sync state. The faceless JSON create was removed. The member page reads applied and pending state from the desired revision and the reader's applied revision instead of the per-reader command and sync-state fields that the desired path never wrote. No physical-reader check applies beyond Section E.

### V19 — Attendance poll

**Behavior.** Punches reach admins soon after they happen. The gateway polls one time window per reader, starting from the last stored punch time with a small overlap. A window that returns nothing writes nothing. Rows are append-only. Attendance never writes member state and never blocks the member worker.

**Prerequisites.** V16.

**Backend.** An ingest endpoint stores punches idempotently on the existing `(tenant, device, fingerprint)` key and links them to a member through the mapping. An unmapped `deviceUserId` is stored without a member. The cursor moves only forward.

**Gateway.** The poll runs on its own schedule beside the reader worker and shares the reader's connection lock. `stuTime` is treated as UTC. It does not ingest `ALARM_ACCESS_CTL_EVENT`, does not query after a record number, and does not clear the reader log. The poll interval is configuration, and the default is short enough for admins to see recent check-ins.

**Adapter.** `QueryAttendance(fromUtc, toUtc)` on the fake reader.

**Frontend.** The existing attendance list, refreshed on a short interval.

**Database.** Existing attendance tables.

**Tests.** A repeated window inserts no duplicate. An empty window writes nothing. A punch by an unmapped id is stored unlinked. A member write during a poll is not delayed beyond the lock. No member or mapping row changes during ingest.

**Acceptance criteria.** A punch on the fake reader appears once in the attendance list.

**Done.** Tests green on the fake reader. Record-number survival across a reboot or full log stays open (Section D).

Automated tests passed on the fake reader and test database. `AttendanceWindowIngestIT` (12) covers the following: a repeated window stores nothing twice; an empty window writes nothing and creates no cursor; an unmapped id is stored unlinked and creates no member; ingest changes no member, mapping, or desired row; the cursor only moves forward; a punch dated after the server's clock is stored but does not move the cursor, and a later real punch is still stored and moves it; a window of only future-dated punches leaves the cursor where it was; a batch of 500 is stored and a batch of 501 is refused with nothing written; a record number seen again at another time is a new punch; a punch stored before polling is not stored again; a punch appears once in `/api/v1/attendance`; and only the reader's own gateway may post. `AttendancePollTests` (15) in the gateway suite covers the following: a repeated window posts each punch once; an empty window posts nothing; the window starts at the last stored punch less the overlap (or the first lookback); a failed read posts nothing and the same window is read again; a failed upload is sent again; a window of 1,201 punches is uploaded oldest first as 500, 500 and 201; when the second batch fails, the cursor stays at the last punch of the first batch, and the next poll sends the remaining 701 with no duplicate; a future-dated punch does not stop later real punches from being read; a last stored time ahead of the gateway clock never starts a window in the future; an unknown reader user's punch is still sent; the poll only queries and never writes or clears the reader; and a member write waits for the attendance query but not for the upload. `LiveReaderAdapterTests` covers the live adapter's record search through a scripted SDK search: a search that finds nothing is an empty window; a failed search, a failed later page, and a record with an unreadable time each fail the whole window instead of returning part of it; pages are read to the end and returned oldest first as UTC; and a failed live read reaches the poll as a failed read with no punches. The acceptance case runs `GatewayWorker` over HTTP: a fake-reader punch is posted once across two polls. In Playwright, a stored punch appears once in the attendance list on its 15-second refresh, without a reload.

How it is built:
- The server stores a window at `POST /internal/gateway/attendance` and returns the reader's last stored punch time at `GET /internal/gateway/attendance/cursor`.
- The fingerprint is user, time, method, result, and record number together. A record number alone is not trusted as the key, because its survival is unproven. A row stored by the old event path is recognized so it is not stored twice.
- The gateway polls each reader every `AttendancePollSeconds` (default 15). The window runs from `AttendanceOverlapSeconds` (default 120) before the last stored punch to now, or from `AttendanceFirstLookbackHours` (default 24) back when the reader has no stored punch. Each reader call runs under the same per-reader lock as the member worker, the command dispatcher, and clock sync. The lock covers only the query, never the upload.
- `stuTime` is converted as UTC. The reader clock is not changed.
- Uploads go oldest first, in batches of at most 500 (the server's limit). A failed batch ends the poll. The batches before it are stored, and the server's cursor is at most the last punch of the last stored batch, so it never passes an unsent punch. The next window starts there less the overlap, and punches already confirmed are not sent again. The server also deduplicates every punch.
- A punch dated after the server's clock is stored, logged as a warning, and does not move the cursor. The gateway never starts a window after its own clock, even if the stored time is ahead of it.
- On the live adapter, a failed record search, a failed later page, an unreadable record time, or a reader that is not logged in is a failed read. It is never returned as an empty or partial window. Records are returned oldest first.

What this slice replaced:
- Live access events are not an attendance source. The gateway does not forward them, and the server has no message type for them.
- There is no server-scheduled reader read: no attendance or user-list command, no auto-reconcile scheduler, no reconcile on connect, no `/sync-now`, no roster digest and no record-number cursor. The reader page shows the last stored punch time.

Kept: `attendance_sync_cursor.last_event_at` as the last stored punch time, mapping-based linking, security events for denied and unknown punches, and the staff live broadcast. The columns of the removed fields stay in the schema, with no migration.

Limitations:
- A record whose time cannot be read fails every window that contains it. That reader's attendance stops at that point, with a warning naming the record, until the record leaves the window or someone intervenes. Skipping the record would risk moving past real punches. The recovery policy is a tracked follow-up (Section D).
- It is unverified on hardware whether `FindRecord` on this firmware succeeds for a time window with no records. If it fails, an empty window is reported as a failed read and retried each poll. Nothing is stored or skipped, but the log shows warnings.
- The scripted SDK search tests the adapter's handling of search results. They do not call the native SDK.
- A punch dated after the server's clock is kept and does not move the cursor. If the reader clock is ahead, its punches can still be read and stored, but they do not move the cursor until server time catches up.
- An unknown route still returns 500 instead of 404. That predates this slice.
- No physical reader was polled.

### V20 — A superseded staff decision still closes

**Behavior.** A review item or pending enrollment that a staff decision put into applying state closes once the reader confirms a later revision of the same user. Today it closes only when the gateway acknowledges the exact decision revision. If a member change (for example a rename) publishes a newer revision of that user before the gateway applies the decision, the decision revision is never acknowledged: the gateway refuses it as superseded. The item then stays in applying state forever, even though the reader converges.

**Prerequisites.** V14, V17.

**Backend.** When an acknowledgement of revision R on a reader passes read-back, close every open decision on that reader for the same `deviceUserId` whose decision revision is at or below R. This runs inside the acknowledgement's reader lock. A decision for another device user id, or on another reader, is untouched. A failed read-back of the newer revision records the verification error on the superseded decision too, and leaves it open. A stale acknowledgement that is refused as superseded closes nothing. The decision audit row is unchanged; closing writes no new decision row.

**Gateway.** No change.

**Adapter.** No change.

**Frontend.** No change. The item leaves the open list when it closes.

**Database.** No new table. Settling may need a lookup by reader and device user id among open decisions.

**Tests.** Decide a review item, rename the member before the acknowledgement, and acknowledge the newer revision: the item closes and applied equals the newer revision. The same for a linked pending enrollment. An acknowledgement of the old decision revision is refused and leaves the item applying. A failed read-back of the newer revision leaves the item open with the error. An open decision for another device user id, or on another reader, stays open. A repeated acknowledgement does not change the closed item.

**Acceptance criteria.** No decision stays in applying state after the reader has confirmed a revision that replaced it.

**Done.** Complete, on automated tests (12 October 2026), for decisions acknowledged after V20; decisions stuck before it are not closed retroactively (see below). That is not physical-reader evidence; Section E still applies. Automated tests passed on the test database. `SupersededDecisionClosingIT` (4) covers the following:
- An accept-server decision replaced by a rename: an acknowledgement of the decision revision is refused with 409 and leaves the item applying with no error. The acknowledgement of the rename closes the item, applied equals the rename revision, and the decision, actor, decision revision, and single decision audit row are unchanged. A repeated acknowledgement leaves the closed item untouched (same row version).
- The same for a linked pending enrollment.
- A failed read-back of the rename revision leaves the replaced decision open with the verification error. A later matching acknowledgement closes it.
- On the same reader, an open decision for another device user id stays open even though its decision revision is below the acknowledged one. A decision on another reader also stays open, and both stay in the open review list.

The existing V14, V15, and V17 decision tests pass unchanged.

How it is built:
- The acknowledgement already takes the reader lock and rereads the row at the acknowledged revision.
- After read-back passes, it closes each review item and pending enrollment on that reader for the row's device user id that is not resolved and whose decision revision is at or below the acknowledged revision.
- A failed read-back records the error on the same set, and leaves those decisions open.
- A stale acknowledgement is refused before either step, so it closes nothing.
- Closing sets only the resolved flag (and, for an enrollment, its final status); it writes no decision or audit row.
- Both tables already have a unique key on reader and device user id, so the lookup uses an existing index. There is no migration.

Behavior this slice replaced:
- The exact-revision settle (close only the decision whose revision equals the acknowledged one) is the behavior V20 replaces. The new rule includes the exact case, so the exact lookup was removed along with its two repository methods.
- An item that reopens for a new reader difference already clears its decision and decision revision, so a later acknowledgement cannot close a fresh difference.
- A resolved enrollment does not reopen.
- No gateway, adapter, frontend, or schema change was made, and no review or conflict behavior was added.

Decisions already stuck in applying state before V20:
- V20 closes a decision only when an acknowledgement arrives after it is deployed. A decision whose superseding revision the reader confirmed before V20 stays in applying state.
- No one-time cleanup was written, because the server keeps no record that proves a given user's superseding revision was acknowledged. Checked on 12 October 2026:
  - `reader_revision.applied_revision` is one number per reader. It does not say which user's row was confirmed, so it is not used.
  - `desired_member_projection` holds only the current desired revision. It records no applied revision per row. `observed_face_sha256` is overwritten without a revision, and `updated_at` also changes on every new publish.
  - `device_reader_baseline` holds field values with no revision. It is also written when a reader report already agrees with the desired record, which is not an acknowledgement, and `updated_at` moves only when the values change.
  - `audit_log` has the staff decision rows (`REVIEW_DECIDED`, `ENROLLMENT_DECIDED`) and nothing for acknowledgements.
  - `verification_error` records only failed read-backs. Request access logs name the endpoint and status, not the revision or user.

  Inferring closure from any of these could close a decision the reader never confirmed, so nothing is bulk-closed.
- The gateway's own journal on the gateway host was not inspected; it is not server data.
- Safe staff path:
  1. A stuck item shows as applying with no verification error in the open review list.
  2. It cannot be decided again. The decision endpoints answer 409 "Decision is waiting for the reader", and that is intended.
  3. It closes, with its decision, actor, and audit row unchanged, once a real change to that member reaches that reader and the reader confirms it. For example: a name, membership date, status, or photo change.
  4. Saving the member with no change publishes nothing and does not help.
  5. A rejected enrollment (an absence with no member) has no member change to make, so it stays applying until that device user id is published again.
- Operators can list candidates read-only. These are open decisions on a reader whose user now has a later desired revision: `device_review_item` or `pending_enrollment` rows with `resolved = 0`, `decision_revision IS NOT NULL`, and `verification_error IS NULL`, joined to `desired_member_projection` on `device_id` and `device_user_id` where `revision > decision_revision`. The query shows that the decision was superseded, not that the reader confirmed the newer revision, so it is not a reason to close anything.

Limitations:
- Decisions stuck before V20 are not closed retroactively (above).
- Decisions are matched by device user id. A decision whose row later moved to another id (only the occupied-id retry does that, and decisions keep the reader's existing id) would not be closed by acknowledgements of the new id.
- No physical reader was used.

### V21 — Late changes reach every reader without a new roster

**Behavior.** A member who becomes active, or gets a face, after a reader was seeded is written to every reader of the gym that has a gateway and no mapping for that member. A reader added with people already on it receives the existing active members. Neither waits for another roster. Unknown reader users are never linked or overwritten.

**Prerequisites.** V6, V15, V18.

**Backend.** One reconcile operation per reader. Under that reader's lock, each active member with a face and no mapping on the reader goes through the V18 seed (`seedIfAbsent`). It is idempotent: a second run writes nothing. Triggers: a member becomes active; a member without a face gets one; a reader's trusted list arrives, empty or not; and an explicit staff action on one reader. A trigger that touches several readers takes their locks in ascending device id order. An existing mapping, including a staff removal, is never rewritten. The allocator skips every id seen on that reader. An id the server has not seen is protected by the gateway's no-overwrite write and the occupied retry.

**Gateway.** A trusted non-empty list is uploaded as one list with its announced total, as an empty list already is. Per-user observations stay as they are. A short or mismatched list still uploads no list.

**Adapter.** No change.

**Frontend.** The reader page offers a reconcile action. The bootstrap report keeps its V15 scope (Section D).

**Database.** No new table.

**Tests.** A member inactive when a reader was seeded is reactivated: every reader without a mapping gets the member, with no roster. A member without a face gets one: the same. A staff-removed member is not written again. An unknown user's id is not reused. An unseen id that turns out to be occupied is retried on that reader only. A reader added with people on it receives the existing members after one gateway reconnect on the fake reader. A reconcile racing a create maps each member once. Running the reconcile twice writes nothing the second time.

**Acceptance criteria.** After a late activation or photo, every reader with a gateway reaches that member's desired revision without a new roster. A reader added with people on it reaches the existing active members after one reconnect.

**Done.** Complete, on automated tests (14 October 2026). That is not physical-reader evidence; Section E still applies.

Tests:
- `LateMemberChangesIT` (10):
  - A member who was inactive when a second reader was seeded is reactivated. That reader gets the member with no roster message, and both readers converge.
  - A member reactivated with a frozen membership is written disabled, with the membership dates.
  - A member without a face gets one and reaches both readers; an inactive member who gets a face reaches none. A later photo change publishes a face revision and creates no new mapping.
  - A staff-removed member is not written again by the staff action, an empty roster, or a photo change.
  - An unknown user's id on a reader is not reused and the person is not linked.
  - An unseen id that turns out to be occupied is retried on that reader only.
  - A reader added with people on it receives the existing members from its first list, above the ids it showed, and converges.
  - Running the staff action twice writes nothing the second time; each run is audited.
  - The staff action needs a reader with a gateway and `DEVICE_SYNC`. Staff without it get 403.
  - Five creates, three staff reconciles, a reactivation, a first photo, and a roster, all at once, map each member once per reader with distinct ids and revisions.
- Gateway: `LiveBootstrapTests` covers a reader with people on it that uploads its whole list on one reconnect and applies the member the server seeded, and a list over the server's message limit that is not queued. `BootstrapTests` and `PendingEnrollmentTests` check that a trusted list is uploaded as one list and per user.
- `e2e/one-writer.spec.ts`: the reader page offers "Reconcile members" and reports what was written; staff without `DEVICE_SYNC` do not see it.

How it is built:
- `ReaderReconcileService` is the one reconcile operation per reader. Under that reader's lock it runs every active member through `seedIfAbsent`.
- The bootstrap report, a trusted list, and the staff action use it.
- Reactivation and a member's first photo lock every reader of the gym with a gateway, lowest device id first, then seed that member on the readers that lack them. The access or face publish for readers that already have the member follows in the same transaction.
- The staff action is `POST /api/v1/devices/{id}/reconcile` with the existing `DEVICE_SYNC` permission. It is audited as `DEVICE_RECONCILE_REQUESTED`, an action that had no remaining caller.
- The gateway uploads every trusted list, empty or not, as one `DEVICE_USER_CHANGED` message with `announcedTotal`, on reconnect and on each roster scan. On reconnect the per-user observations and absences are sent before it. On a scan the absences are sent first, then the list, and the per-user observations only when the server did not acknowledge the list (see the follow-up below). A short or mismatched list uploads nothing.
- A seeded member is written with the same reader status the access publish computes. Before this, the seed always wrote enabled, so a reactivated member with a frozen or ended membership would have been let in until the next access change.
- A lock-helper fix: `ReaderRevisions.reload` no longer refreshes a row this transaction already holds the lock on. A second refresh is read without the lock, from the transaction's older snapshot, and replaced the current revision with an earlier one. The concurrent-trigger test failed every run with a concurrent-modification 409 before this fix and passed three runs after it.

Paths that could compete with the reconcile: none. There is no reader user import, no manual mapping and no per-member "read from device"; the outbox carries only staff commands (door, scan now) and never a member. `GatewaySocketCredentialIT` used to add a reader mapping by hand after a first face; the first face now seeds that mapping, so the test checks that it exists.

Follow-up after review (14 October 2026, uncommitted at the time of writing):
- Seed race. `ReaderSeedRaceIT` (7) holds a seed or a member change open at a fixed point and checks the reader afterwards. Before the fix, five of its race cases failed. A deactivation or freeze that committed while a seed waited for the reader, or ran during an uncommitted seed, left the member enabled on that reader. A rename during a seed did not reach it. Two causes:
  - The seed wrote from a member read before it held the reader.
  - The access and face publishes found readers through mappings read from the transaction's snapshot, so they missed a mapping the seed had just committed.
- The fix:
  - The seed rechecks the member's version under the reader lock with `FOR SHARE SKIP LOCKED`. If the member changed, or a change still holds it, the seed leaves that member for the next trigger and logs "seed deferred".
  - Access, face, and face-cleared publishes lock every gateway reader of the gym, lowest device id first, then read the mapping and desired row as current or locking reads.
  - The lock order is unchanged: readers by ascending device id, then member rows. All 7 race tests pass, and `ReaderConcurrencyIT` and the V21 concurrency test still pass.
- Outbound journal coalescing (gateway).
  - The journal keeps only the newest unsent whole list per reader. A newer list replaces the older one on disk and in memory, and a list replaced during replay is not sent.
  - On restart, the newest list per reader wins and older ones are deleted. Per-user observations, absences, and every other message are never coalesced. An acknowledgement for a list that was already replaced leaves the newer list queued, and a message the server rejects stays queued.
  - `OutboundSnapshotTests` (7) covers this, including a one-hour outage: 240 scans, 2 readers, every per-user message delivered once, one final list per reader, and an empty journal afterwards. Four of these tests failed against the previous journal.
- Scan load, measured on the test database (Testcontainers MySQL 8.4 on a developer machine). `RosterScanLoadIT` is opt-in (`-Dgym.measure=true`). It uses one reader with 1,200 mapped members with faces and posts a matching trusted list, so nothing is seeded:

  | Step | Before | After the reconcile prefilter |
  |---|---|---|
  | One 1,200-user list (steady state, 5 rounds) | about 19 s, 9,631 statements | about 8.2 s, 6,032 statements |
  | Staff reconcile, nothing to write | about 5.2 s, 3,615 statements | about 26 ms, 16 statements |
  | 1,200 per-user messages (the scan sends these before the list) | about 43 s, 31,204 statements | unchanged |

  - With 300 users, a list took about 2.2 s and the per-user messages about 7 s. Cost grows faster than the user count.
  - The prefilter: the per-reader reconcile reads the ids of members already mapped on that reader once, with `FOR SHARE`, after taking the reader lock. It then calls `seedIfAbsent` only for the others. Mappings are never deleted and are only added under the reader lock, so this skips exactly the members the per-member locked count skipped and holds the same share locks. Seeding rules are unchanged; the existing V18, V21, and race tests pass.
- List-only scan (approved and built 14 October 2026, uncommitted). A scan no longer sends the per-user observations when the server acknowledged that scan's whole list.
  - Protocol trace. Every `DEVICE_USER_CHANGED` message goes through `GatewayMessageService` (duplicate message ids are acknowledged without processing), then `DeviceUserChangeService.apply` in one transaction, then `PendingEnrollmentService.observe`. A per-user message runs `observeOne`: save the reader snapshot; for an id the server allocated, record review differences; for any other id, a pending enrollment. For each entry, the list runs the same `observeOne`. Differences, all on the list side:
    - Its announced total must match its entries, or no entry is processed. The gateway sends neither the list nor the per-user messages for a mismatched read.
    - It takes the reader lock before the first entry and holds it to commit. Per-user messages take no reader lock.
    - It also stores whether the trusted list was empty, records absences for desired rows missing from it that were seen or verified before, and seeds missing active members.
    - It is one transaction, so one failing entry rejects the whole list. Per-user failures are isolated.
    - The gateway's absence messages use a different rule: ids its journal applied that the list lacks, checked on the server against every server-allocated id. They are still sent on every scan.
  - Acceptance means the server's ACK naming the list's message id. The server sends it only after the list's transaction committed. A REST response counts only if its body is that ACK. Writing the frame, a REST 2xx without an ACK body, or a durable copy in the outbox is not acceptance. Acceptance says nothing about the reader: reader application and its read-back acknowledgement go through desired revisions as before.
  - Fallback. The per-user messages are sent after the list, as before, in each of these cases:
    - the list was not sent because it is over the size limit;
    - the server rejected the list (ERROR, matched by correlation id) or returned a non-2xx status;
    - the reply was a 2xx without an ACK;
    - the server was unreachable;
    - the connection closed before the ACK;
    - a newer list of the same reader replaced this one in the outbox;
    - no ACK arrived within 60 seconds;
    - the upload failed in any other way.

    The list itself stays under the existing outbox and retry rules.
  - Reconnect always sends the per-user messages first, then the list, as before, because the gateway reads the ACK only after the reconnect step and the replay finish. Acceptance is per scan and in memory only, so after a restart nothing is skipped until a new list is acknowledged. Absences are sent with every list (see change-only scans below for when a scan sends nothing).
  - Tests:
    - `RosterAcceptanceTests` (17, gateway) covers the scan path, every fallback case above, a restart, and over a real WebSocket: an ACK, a timeout, a rejection, a replaced list, and a dropped connection followed by replay.
    - The gateway end-to-end test in `LiveBootstrapTests` uses an acknowledging and a non-acknowledging backend.
    - `TrustedListOnlyScanIT` (5, backend):
      - The same reader report sent as per-user messages and as one list leaves identical rows in every observation table, from snapshots and pending enrollments to review items, baselines, mappings, desired rows, the reader cursor, and members.
      - Unknown users stay unlinked, a staff removal stays removed, a frozen member stays disabled, and a different name opens a review item.
      - A rename racing a list, in either order, is kept and not flagged; a deactivation racing a list leaves the member disabled.
  - Measured on the test database, 1,200 mapped users, 3 rounds. The reader lock is the time some transaction held a row lock on `reader_revision`, sampled every 5 ms:

    | Scan | Server time | Statements | Reader lock held |
    |---|---|---|---|
    | Before: 1,200 per-user messages, then the list | 45.9–52.5 s | 37,236–37,240 | 8.7–14.0 s (the list only; per-user messages took no reader lock) |
    | After: the list only | 8.2–8.7 s | 6,036 | 8.2–8.7 s |

    The reader-lock hold is the list's own and is unchanged. Staff reconcile with nothing to write: about 40 ms, 20 statements (the sampler's connection adds a few).
- Change-only scans and reader photo checksums (approved and built 14 October 2026, uncommitted).
  - Why. Every 15-second scan downloaded every photo from the reader to hash it, and sent the whole list, about 350 KB for 1,200 users, over the gym's internet link. The gym PC probe on 4 October measured the full photo download at about 100 s on one reader and 300 s on the other. The same probe showed the reader's photo index lists the MD5 of every photo in about 4 s without transferring any photo (see [device-poc-results.md](device-poc-results.md#photo-checksum-index)). The architecture already said scans must not be the heavy, primary mechanism.
  - Photos. A scan reads the photo index once (`ListFaceChecksums`). A user it does not list has no photo. A photo is downloaded only when its checksum is not in the gateway's cache. The cache maps a reader checksum to the SHA-256 the server already uses, so the server and the protocol are unchanged. It is keyed by checksum, not by user, so an entry is only reused for the same bytes. It is saved next to the reader journal (`<reader>.faces.json`), so a restart does not download every photo again; a missing or unreadable file starts empty. A failed photo read sends no hash for that user and is read again on the next scan. If the photo index cannot be read completely (a failed call, a page that repeats or blanks a user, or a count that differs from the announced total), the scan downloads every photo as before.
  - Uploads. A scan computes one fingerprint over the announced total, every entry (name, status, validity, authority, photo hash), and the absences. If the server acknowledged a list with that fingerprint within `RosterRefreshMinutes` (default 60), the scan sends nothing. Otherwise it sends the absences and the list as before. When the list is not acknowledged, the per-user fallback sends only users whose entry differs from the last one handed to the outbound journal, and every user once per refresh period. A user whose upload threw is sent again on the next scan.
  - Reconnect is unchanged: per-user messages, absences, then the list. It also forgets the acknowledged fingerprint, and an acknowledgement for a scan that began before the reconnect is not recorded. A restart forgets it too, so the first scan after a restart sends the list.
  - The hourly refresh keeps the server's own per-list work running: seeding a reader that is still missing members, and recording absences. Nothing on the server expires on `observed_at`.
  - The scan interval is `RosterPollSeconds`, default 300 (5 minutes; the floor is 15). An unchanged reader costs two reads over the gym LAN (user list and photo index) and nothing over the internet. A change made at the reader (a user enrolled or edited on the device) is noticed within one interval.
  - Scan now. Staff with `DEVICE_SYNC` can ask for one reader from its page ("Read reader now"): `POST /api/v1/devices/{id}/scan` queues a `SCAN_READER` command (an open one is reused, not duplicated; audited as `DEVICE_SCAN_REQUESTED`). The gateway runs the same scan for that reader, but sends the list even when the server already acknowledged it, and answers `SYNC_RESULT` succeeded once the list has been handed to the server (acknowledged, or followed by the per-user fallback when it was not). It fails when the reader's list call fails or its count differs from the announced total, so nothing was uploaded. The scan runs outside the command's reader lock because each reader call takes that lock itself. Commands for that reader queue behind it (up to the 60 s list acknowledgement wait); other readers are not affected. It does not change the timed scans.
  - Tests: `ChangeOnlyScanTests` (15, gateway) covers one download per unchanged photo, a changed photo, the fallback without a photo index giving the same hashes, a failed photo read, the cache across a restart and when unreadable, the production adapter chain passing the index through, an unchanged acknowledged list sending nothing, a rename, new photo, and new absence each sending the list, the refresh period, per-user fallback sending only changes, a failed per-user upload, reconnect, a scan acknowledged after a reconnect began, and a short list. `LiveBootstrapTests` adds an end-to-end scan that sends nothing the second and third time and downloads the photo once. Two earlier tests that expected an identical acknowledged list to be sent again were changed to the new behavior.
  - Not measured on a physical reader. The `ListFaceChecksums` paging code follows the probe tool that was measured, but this adapter method itself has not run against a reader.
- Optimizations proposed, not made:
  - Load the reader's snapshots, mappings, desired rows, and baselines once per list instead of once per user. This would shorten the 8 s list and its reader-lock hold.
  - Do not rewrite a snapshot whose fields are unchanged; today `observed_at` changes it on every scan.

Limitations:
- A trusted list over about 1 MB (roughly 3,000 ASCII users at the measured field sizes; fewer with non-ASCII names, which the gateway escapes) is not uploaded as one list. The server closes the gateway socket on larger messages. Such a reader is not seeded by its list; the staff action still works. The measured reader holds about 1,200 users.
- Scan load. At 1,200 users, a list costs the server about 8.5 s on the test database and holds that reader's lock for that time, so a member change that publishes to that reader waits up to that long. A list is now sent only when the reader changed, after a reconnect or restart, or once an hour. A fallback to per-user messages costs about 50 s when every user is sent (the first time, and once per refresh period); otherwise only changed users. Every reconnect still costs about 50 s. Production database speed was not measured, and neither was a physical reader.
- During an outage nothing is acknowledged, so each scan queues a list (coalesced to the newest) and per-user observations only for users that changed, plus every user once per refresh period. After reconnect, the queued per-user messages replay after the fresh list.
- A REST reply that is a 2xx with an ERROR body clears that message from the outbox. This was already the case before V21. For a list, the per-user fallback covers it; for other messages it is unchanged.
- Acceptance waits up to 60 seconds per reader, one reader after another, so a slow server delays the next reader's scan by up to that long.
- A reader created while a deactivation is in progress is not locked by that deactivation, because the reader list is read from the transaction's snapshot. No test covers this case.
- A member created from a pending enrollment, or linked to one, is written only to that reader by the decision. The next trusted list from another reader, or the staff action, now seeds that member there too, from the server's photo.
- Membership changes alone do not seed a reader. Seeding follows the member's status and face, not the membership.
- No physical reader was used.

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

**Member writer.** The desired-state worker creates this user. No other component writes the same reader for this member.

**Done.** The tests are green on F2, and the adapter log shows one user create and one face insert for the successful revision.

## D. Remaining gaps

These are not defined tightly enough to pretend they are already specified.

| Gap | Where it stands |
| --- | --- |
| Device-id allocator | The architecture left the exact allocator open. This plan defines highest-known-plus-one decimal ids, plus no-overwrite retry. Server-side writers of one reader (allocation, revision bump, acknowledgement, occupied retry) take that reader's `reader_revision` row lock, and the allocator reads taken ids with locking reads. `ReaderConcurrencyIT` covers concurrent creates, renames, acknowledgements, and an occupied retry on one reader on the test database. A live race against the reader's own screen enrollment was not run. |
| Cross-reader lock order | An operation that touches several readers (a member change on every mapped reader, the bootstrap report) takes the reader locks in ascending device id order, then rereads the projection row and mapping under the lock. Before this, renames of two members mapped in opposite reader order failed with a MySQL deadlock. `ReaderConcurrencyIT.renamesOfMembersMappedInOppositeReaderOrderDoNotDeadlockOrLoseUpdates` runs 10 concurrent rename rounds per member across two readers and checks every final name and a gap-free revision count on both readers. |
| Rename against an older acknowledgement | An acknowledgement takes the reader lock first and rereads the revision under it. An acknowledgement of a revision that a rename has since replaced is refused with 409 "Revision is not the desired member" and leaves the applied revision unchanged; it never moves applied backward. A repeated acknowledgement of the current revision returns 200 each time. `aRenameRacingAnAckOfTheOlderRevisionNeverLosesOrRegressesState` races the two freely; `anAckBlockedBehindARenameOfTheSameRowIsRefusedAsSuperseded` forces the acknowledgement to wait behind the rename. Without the reread, that test got a generic concurrent-modification 409 instead. A decision whose revision a later revision of the same user replaced now closes when the reader confirms that later revision (V20). |
| `publicId` as `szUserID` | Not supported here. UUID form was never written to this reader, and the face user-id type is a 32-character buffer. |
| Bootstrap report scope | Intentional, as V15 defined it. The report classifies a trusted roster into matching, different, unlinked, and collision rows for staff, and seeds only a reader whose last list was trusted and empty. Seeding a reader that already holds people, and late activations or photos, belong to the V21 reconcile. The report's seed goes through the same per-reader reconcile operation; the report does not add rows for readers that already hold people. |
| Which readers receive a new member | Every reader of the gym that has a gateway (V18). The create flow does not ask for a reader. A pending enrollment and a review decision write only their own reader; since V21, the next trusted list from another reader, or the staff reconcile, seeds that member there from the server's photo. |
| Roster size and scan load | Since V21 a trusted list is sent as one message on every scan, and the per-user messages only when the server did not acknowledge it. A list over about 1 MB is not sent as one list. Measured on the test database with 1,200 users: about 8.5 s per acknowledged scan, holding that reader's lock throughout (about 50 s before this change, or on a fallback). Not measured on production hardware or a physical reader. |
| Who may approve review items, and whether an enrollment expires | Gym admin and staff decide review items (V17). A pending person on a reader does not expire. The gateway enrollment token expires after 24 hours. |
| Safety-scan interval | Not chosen. A safety scan is an occasional full user-list read for a missed alarm. The only measurement is about 1,200 users in 3–5 seconds. Do not hardcode 15 seconds. |
| Offline copy to a second reader | Not allowed. The server publishes desired state. The gateway does not copy an unlinked person or resolve that conflict while the server is down. |
| Create without a face | Not allowed. Deleting a photo that was already stored is a face-clear revision on readers that already have the member. |
| Door and time-section fields beyond the probe's create | V1 sends the same `nDoorNum=1` and `nTimeSectionNum=1` the probe used. Other schedules are not verified. |
| SDK reconnect | One power cycle reconnected and one did not. V6 re-reads and pulls. It does not depend on the old login surviving. |
| Attendance cursor across reboot or a full log | Unproven. V19 polls by time window from the last stored punch time, so it does not rely on record numbers, and its fingerprint does not trust a record number alone. Record-number survival across a reboot or a full log, and the reader's retention cap, are not measured. |
| Decisions stuck in applying before V20 | Not closed retroactively. The server records no per-user acknowledged revision, so whether the reader confirmed a superseding revision before V20 cannot be proven. They close on the next confirmed change of that user on that reader. The evidence checked and the staff path are under V20. Recording the acknowledged revision per row would be needed to prove such cases in future; that is not built. |
| Unreadable attendance record time | Tracked follow-up; the safety check stays. A record whose `stuTime` cannot be read fails every window that contains it, so that reader's attendance stalls with a warning naming the record. It is never skipped, and the current time is never substituted, because either could move the cursor past real punches. How staff recover from this (for example, an explicit, audited skip of one named record) is not decided. Whether this firmware can produce such a record has not been observed. |
| `emAuthority` | The menu asks for the reader password. V1 sends `Customer`. Authority changes are observations later, not a projection. |

## E. Physical-reader confirmation

Required once, on a reader, before that reader is treated as validated for live member sync. The fake adapter is not a substitute. This pass has not been run as a confirmation of F1–V17.

- V1 create: the allocated numeric id, full user read-back, and face read-back.
- V2 freeze and enable.
- V3 dates stored as sent.
- V4 face replace and `PHOTO_EXIST`.
- Face removal: the user stays, the photo read is the missing-photo result (`UNKNOWN`, SDK `0x800004B5`), and a second remove of an already-missing photo succeeds.
- V6 reconnect read-before-write.
- V19 attendance poll, through the Windows gateway build with the live adapter, on serial `TW30000005250265`:
  - A genuinely empty window: a time range with no punches returns an empty, successful read. This is the first thing to establish: if `FindRecord` fails for an empty range on this firmware, the poll reports a failed read and retries every interval.
  - Oldest-first order and paging: a window with more punches than one SDK page (20) returns every punch exactly once, oldest first, and the server stores each once.
  - A failed search and a failed later page (for example, the network dropped during the query): the poll posts nothing from that window, the cursor does not move, and the next poll reads the same window again.
  - Reboot: punch, power-cycle the reader, punch again. Both punches are stored once, nothing is duplicated after the reconnect, and the record numbers before and after are noted for the Section D question.
  - Punches near or ahead of server time: a punch within seconds of server time is stored, and one later punch is still stored. The reader clock is not moved to test "ahead": compare the punch `stuTime` with server time and record the offset seen. Do not call `SynchronizeTime` to set the reader to UTC.
  - Retention (how many records the log keeps, and what happens when it is full) is not tested on this unit, because its log must not be filled. It needs a spare reader.

Status: not run. On 12 October 2026 this pass could not be run from the development machine: the NetSDK ships only Windows libraries (`gateway/native/win-x64`), the machine is Linux, and a reboot needs someone at the reader. None of the items above have physical-reader evidence.

The 7 October 2026 probe measured related SDK behavior on serial `TW30000005250265`. That run is evidence for the design. It is not this confirmation pass. V8 through V15 do not need a new hardware experiment. Do not factory-reset this unit and do not fill its log to test them.

## Release 1 cleanup (15 October 2026, uncommitted)

Release 1 has no earlier install to stay compatible with, so code that only refused, cancelled or translated earlier flows was removed:
- Shared gateway token: the `app.gateway.shared-token` (`APP_GATEWAY_SHARED_TOKEN`) check in the production guard is gone; nothing reads that setting. A gateway authenticates only with its own credential. A WebSocket token in the query string is still refused.
- Outbox: `SyncCommandType` is `OPEN_DOOR`, `CLOSE_DOOR` and `SCAN_READER`. A command row has no member or membership. The member-write, attendance, clock and clear-log command types, their refusal and cancel paths, and the gateway dispatcher's refusal lists are removed. The gateway still sets each reader's clock to local time on its own schedule.
- Protocol: `DEVICE_EVENT`, `RECONCILIATION_RESULT` and `ENROLLMENT_RESULT` are removed. `SYNC_RESULT` carries `ok` and `error` only.
- Removed features: reconciliation conflicts (table, endpoints, reader-page list), reader user import and manual mapping endpoints, the device-user snapshot table, member `creationSource` (and the Device/Manual badges), membership `endDateInferred` and `deviceSyncState`, mapping enrollment and face-sync fields, the `PUT /memberships/{id}/dates` endpoint (`PUT /memberships/{id}` edits dates), and the per-member `POST .../read` endpoint.
- Gateway: the `Remote` simulator adapter (its device server was removed earlier) is gone; `appsettings.json` defaults to `Mock`. The end-to-end harness project is `Gym.Gateway.ReaderHarness`. The live adapter still raises access events for the Windows POC's walk tests; the gateway does not forward them.
- Schema: `V1__baseline.sql` was edited in place. A database created from the previous baseline must be recreated.
- Tests: tests that only checked a refusal of a removed path were deleted. Test classes and test data no longer carry slice labels.

## Initial import from readers (15 October 2026, uncommitted)

The first install starts with an empty server and readers that already hold people, on one reader or both. When the gateway connects, each reader's whole list is uploaded and every person the server does not know becomes a pending enrollment, as before. On top of that:
- Import: `POST /api/v1/devices/{id}/import-pending` (gym admin: `DEVICE_SYNC` and `REVIEW_DECIDE`), with an "Import pending people" button on the reader page, runs one staff create per undecided person on that reader. A person whose photo has not reached the server, or whose decision is still waiting for the reader, stays pending and is listed. Nobody is linked.
- Create from a reader, single or imported, adopts the reader's record as the desired member: name, dates, enabled or disabled, authority, door and time section, and photo. The gateway reads it back as matching and writes nothing. A person the reader shows disabled becomes an inactive member. The gateway's list upload now carries `doorNum` and `timeSectionNum`.
- A member without a membership keeps the dates a reader already holds for them when they are linked or seeded to another reader, instead of today only. A staff edit writes the change and keeps those dates until a membership exists.
- A person on both readers is imported from one reader and linked on the other with Link, under that reader's own id; `member_device_mapping` holds one row per reader. While a reader has undecided people, the automatic seed after its trusted list skips members created from another reader's people, so an imported member is not copied onto it under a second id; server-created members are seeded as before. Once staff have decided on everyone that reader showed, imported members it still lacks are seeded with the dates they already hold. Staff Reconcile is unchanged.
- A reader-side change of enabled/disabled or of the photo on a mapped member opens a review item, as name and date changes already did. The review page shows both.

## F. Fake reader only

F1's credential tests, F2, F3, and V1 through V21 were accepted on the fake reader and the test database. That acceptance does not close section E.

## G. Remaining gates

The slice checks through V21 are recorded as done on the fake reader and test database in each slice above. On 9 October 2026, before V18, `ReaderConcurrencyIT` (7 tests) passed in 5 repeated runs with no deadlock, then `mvn clean verify` passed (33 unit, 159 integration) and the gateway suite passed (129). After V18, including seeding readers added later, `mvn clean verify` passed (33 unit, 171 integration), the gateway suite passed (129), the frontend build passed, and Playwright passed (10 passed, 1 skipped that needs a live API). On 12 October 2026, after V19, `mvn clean verify` passed (33 unit, 179 integration), the gateway suite passed (139, three repeated runs), the frontend build passed, and Playwright passed (11 passed, 1 skipped that needs a live API). Later on 12 October 2026, after the V19 fixes for batched uploads, future-dated punches, and live read failures, `mvn clean verify` passed (33 unit, 182 integration), the gateway suite passed (149, three repeated runs), the frontend build passed, and Playwright passed (11 passed, 1 skipped that needs a live API). On 12 October 2026, after V20, `mvn clean verify` passed (33 unit, 186 integration), the gateway suite passed (149), the frontend build passed, and Playwright passed (11 passed, 1 skipped that needs a live API). On 14 October 2026, after V21, `mvn clean verify` passed (33 unit, 196 integration), the gateway suite passed (151), the frontend build passed, and Playwright passed (13 passed, 1 skipped that needs a live API). Later on 14 October 2026, after the V21 follow-up (seed race fix, journal coalescing, reconcile prefilter), `mvn clean verify` passed (33 unit, 204 integration, 1 skipped: the opt-in `RosterScanLoadIT`) and the gateway suite passed (158). `ReaderSeedRaceIT`, `ReaderConcurrencyIT`, and `LateMemberChangesIT` (25 tests) passed in 3 repeated runs. The frontend and Playwright were not rerun because no frontend code changed. After the list-only scan, `mvn clean verify` passed (33 unit, 209 integration, 1 skipped: the opt-in `RosterScanLoadIT`), and the gateway suite passed (176) in 3 repeated runs. This is test-database evidence only. What remains:

1. Section E, on a physical reader, before that reader is treated as validated.
2. The remaining choices in [open-questions.md](open-questions.md): the safety-scan interval, door and time-section values other than 1, SDK reconnect as a guarantee, and attendance record-number survival and retention.
3. A recovery policy for an attendance record whose time cannot be read (Section D). Until it is decided, such a record stalls that reader's attendance rather than being skipped.
4. The roster size limit and the scan load from V21 (Section D). Measured on the test database only. The list-only scan is built, but not checked on a physical reader or production hardware.
