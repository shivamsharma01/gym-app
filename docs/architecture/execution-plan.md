# Execution plan

Status: F1–F3 and V1–V17 are implemented. Their automated tests passed on the fake reader and the test database. V18, V19, and V20 are not built. Section E has not been run on a physical reader.
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

**Done.** Automated tests passed on the fake reader and test database: `RbacSeedIT`, the three staff tests in `V14StaffDecisionIT`, and the staff case in `e2e/review.spec.ts`. No physical-reader check applies beyond Section E.

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

**Done.** Tests green on the fake reader.

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

**Done.** Not built.

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
| Rename against an older acknowledgement | An acknowledgement takes the reader lock first and rereads the revision under it. An acknowledgement of a revision that a rename has since replaced is refused with 409 "Revision is not the desired member" and leaves the applied revision unchanged; it never moves applied backward. A repeated acknowledgement of the current revision returns 200 each time. `aRenameRacingAnAckOfTheOlderRevisionNeverLosesOrRegressesState` races the two freely; `anAckBlockedBehindARenameOfTheSameRowIsRefusedAsSuperseded` forces the acknowledgement to wait behind the rename. Without the reread, that test got a generic concurrent-modification 409 instead. A review decision whose revision is superseded by a later revision of the same row is still never settled; that predates this work and is tracked as V20. |
| `publicId` as `szUserID` | Not supported here. UUID form was never written to this reader, and the face user-id type is a 32-character buffer. |
| Which readers receive a new member | Every reader of the gym (V18). The create flow still asks for one reader until V18 is built. |
| Who may approve review items, and whether an enrollment expires | Gym admin and staff decide review items (V17). A pending person on a reader does not expire. The gateway enrollment token expires after 24 hours. |
| Safety-scan interval | Not chosen. A safety scan is an occasional full user-list read for a missed alarm. The only measurement is about 1,200 users in 3–5 seconds. Do not hardcode 15 seconds. |
| Offline copy to a second reader | Not allowed. The server publishes desired state. The gateway does not copy an unlinked person or resolve that conflict while the server is down. |
| Create without a face | Not allowed. Deleting a photo that was already stored is a face-clear revision on readers that already have the member. |
| Door and time-section fields beyond the probe's create | V1 sends the same `nDoorNum=1` and `nTimeSectionNum=1` the probe used. Other schedules are not verified. |
| SDK reconnect | One power cycle reconnected and one did not. V6 re-reads and pulls. It does not depend on the old login surviving. |
| Attendance cursor across reboot or a full log | Unproven. V19 polls by time window, so it does not rely on record numbers. |
| `emAuthority` | The menu asks for the reader password. V1 sends `Customer`. Authority changes are observations later, not a projection. |

## E. Physical-reader confirmation

Required once, on a reader, before that reader is treated as validated for live member sync. The fake adapter is not a substitute. This pass has not been run as a confirmation of F1–V17.

- V1 create: the allocated numeric id, full user read-back, and face read-back.
- V2 freeze and enable.
- V3 dates stored as sent.
- V4 face replace and `PHOTO_EXIST`.
- Face removal: the user stays, the photo read is the missing-photo result (`UNKNOWN`, SDK `0x800004B5`), and a second remove of an already-missing photo succeeds.
- V6 reconnect read-before-write.

The 7 October 2026 probe measured related SDK behavior on serial `TW30000005250265`. That run is evidence for the design. It is not this confirmation pass. V8 through V15 do not need a new hardware experiment. Do not factory-reset this unit and do not fill its log to test them.

## F. Fake reader only

F1's credential tests, F2, F3, and V1 through V17 were accepted on the fake reader and the test database. That acceptance does not close section E.

## G. Remaining gates

The slice checks through V17 are recorded as done on the fake reader and test database in each slice above. On 9 October 2026, before V18, `ReaderConcurrencyIT` (7 tests) passed in 5 repeated runs with no deadlock, then `mvn clean verify` passed (33 unit, 159 integration) and the gateway suite passed (129). This is test-database evidence only. What remains:

1. Section E, on a physical reader, before that reader is treated as validated.
2. The remaining choices in [open-questions.md](open-questions.md): the safety-scan interval, door and time-section values other than 1, and SDK reconnect as a guarantee.
3. V18 every-reader create, then V19 attendance poll.
4. V20 superseded staff decisions close.
