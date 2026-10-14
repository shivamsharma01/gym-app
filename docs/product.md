# Product follow-ups

Open product decisions and remaining work after the original 0–9 roadmap.

## Remote face enroll (decision)

| Finding | Status |
| --- | --- |
| Live visit 2026-09-13 on serial `TW30000005250265` | Remote JPEG INSERT returned SDK success (`0x00000000`); door recognized the person |
| Product rule | The server stores one photo per member. A new member is published to every reader of the gym (V18). The create flow does not ask for a reader. |
| Gateway face write | Real `OperateAccessFaceService` writes. The image is downloaded with a sha256 check. A second insert over an existing photo is not success. An empty update does not clear the photo. |
| Guided enroll command | Not used. Face changes are part of the desired revision. |

See [TrueFaceWindowsPOC/docs/GYM-VISIT-2026-09-13.md](../TrueFaceWindowsPOC/docs/GYM-VISIT-2026-09-13.md).

## Member and face sync

The design is [the device sync architecture](architecture/device-sync-architecture.md). Slice status is in [the execution plan](architecture/execution-plan.md).

- One gym has one gateway and can have several readers. Each reader has its own worker.
- `publicId` is the member. `deviceUserId` belongs to one reader. The mapping is the link. A device user id is never a member id, and it is not the member code.
- The server publishes a desired projection and a revision for each reader. The gateway writes that projection, reads the user and the face back, and acknowledges only when they match. A stale revision does not overwrite a newer one.
- A new member goes to every reader of the gym. Each reader gets its own device user id, mapping, and revision. The create flow does not ask for a reader, and the member page shows whether each reader applied the member or is still waiting.
- A person created on a reader stays a pending enrollment on that reader until staff link, create, or reject them. The reader’s id is kept. They are not copied to another reader.
- A name, face, freeze, or disappearance that disagrees with the desired record becomes a review item. Staff decide. The decision is a new desired revision and stays open until the reader verifies it. Clocks do not pick a winner.
- Removing someone from one reader does not deactivate the member and does not write the other readers.
- Freeze keeps the same device user and the same face and sets the reader status to frozen. Enable sets it back. That is separate from reader-menu administration, which is the reader password.
- A list that fails, is short, or does not match its announced count changes nothing. Only a trusted roster, empty or not, may be followed by seeding server members onto that reader.
- The same face on two device user ids is two people until staff say otherwise.
- Attendance punches are stored separately and do not change member state.

Access dates sent to a reader still come from the member’s memberships: the running one, otherwise the next, otherwise the last. Back-to-back paid memberships join into one window. The end of the day on the reader is 23:59:59 in the gym’s local time (`GYM_TIMEZONE`, default Asia/Kolkata). The reader clock stays on that local time. Do not set it to UTC.

### Checks still to run on a reader

These are not done. The fake-reader tests for V1–V16 passed. Section E of the execution plan is the physical pass.

1. Create a member with a photo on one selected reader. Read-back matches the allocated numeric id, the name, the dates, and the face. `publicId` is not the device user id.
2. Freeze, then enable. The same user and face remain. The door follows the status on this firmware, as in the 7 October 2026 probe.
3. Change the name and the dates. The reader stores them as sent, including 23:59:59.
4. Replace the face. A second insert over an existing photo is not success. Read-back matches the new bytes, or the bytes the reader stored if it re-encoded them.
5. Disconnect the gateway, change the member on the server, reconnect. The gateway reads the reader before it writes. An older revision does not undo a newer one.
6. Enrol someone on the reader. They appear for review and are not created as a member until staff decide. The other reader is not written.
7. Rename a mapped user on the reader. The member in the app stays as it was until staff accept a review decision, and that decision is verified on the reader.

## ACCESS events

Live door allow can succeed without the SDK delivering `ALARM_ACCESS_CTL_EVENT` to our listener. The Windows POC now:

1. Waits for live ACCESS callbacks
2. Polls attendance records as a fallback
3. Accepts operator `YES` confirmation for grant/deny when neither yields evidence

Attendance ingestion should use the same defense in depth: do not treat a missing live alarm as a missing punch. The 7 October 2026 run stored punches and received no `ALARM_ACCESS_CTL_EVENT`.

The gateway therefore does not use live access events as attendance (V19). It reads each reader's attendance log by time window on its own short interval (`AttendancePollSeconds`, default 15), starting a little before the last stored punch. The server stores each punch once and links it to a member through the reader mapping; a punch by an id with no mapping is stored without a member. The attendance list refreshes every 15 seconds. The reader log is never cleared, and attendance never changes members or mappings. There is no manual attendance reconcile.

## People already on a reader

A trusted roster becomes a bootstrap report. A matching mapping stays on the desired projection. A different name becomes a review item. An unlinked person becomes a pending enrollment. The same device user id for different people on two readers is a collision report; the mappings stay separate. Two members with the same name are not linked automatically.

A reader with a trusted roster is seeded with every active member it does not have yet, through the desired-state writer. The server allocates a new device user id on that reader, above every id the reader showed. It does not copy ids from another reader. People already on the reader stay as they are and are not linked to a member; the same person can therefore appear twice until staff decide the pending enrollment. A short or mismatched list creates nothing. The gateway sends every complete list as one message, so a reader added with people on it is seeded after one reconnect. A member who is reactivated, or gets a first photo, is written to every reader that lacks them without waiting for a list. Staff with device sync permission can also reconcile one reader from its page. A member deactivated, frozen, or renamed while a reader is being seeded ends up in their new state on that reader. While the server is unreachable, the gateway keeps only the newest whole list per reader; every other message is kept. A scan that finds a reader exactly as the server last acknowledged it sends nothing over the internet; the list is still sent at least once an hour and after every reconnect. The gateway compares the reader's own photo checksums and downloads a photo from the reader only when its checksum changed. Each reader is scanned every 5 minutes by default, so a person enrolled or changed at the reader can take up to 5 minutes to appear. Staff with device sync permission can use "Read reader now" on a reader's page to scan it immediately; that scan sends the whole list even if nothing changed.

Once the server confirms it has processed a scan's whole list, the gateway does not also send each person separately; if that is not confirmed, it still does. On the test database, a reader with 1,200 people now costs the server about 8.5 seconds per scan instead of about 50, and that reader stays locked for member changes for those 8.5 seconds. A reconnect, or a scan the server does not confirm, still costs about 50 seconds. Physical readers and production hardware were not measured.

Staff link, create, accept the server value, reject, restore, or remove from this reader. Link and create keep the reader’s id. Reject does not create a member. None of these actions are finished until the reader read-back is acknowledged.

Face templates stay on the reader. The server stores one photo per member so a reader can be rebuilt from desired state.

Members list **Source** is Manual or Device. SUPER_ADMIN CSV export has no face data.

## Still open

- Real SMS/email ([notifications.md](notifications.md)); providers are mock
- Real payment provider
- Forgot-password email (the screen tells staff to contact a super admin)
- Super admin acting as a gym
- Changing plan mid-cycle (dates can be edited; the plan cannot)
- Member profile payments omit the paid-on date
- Scan load for readers with many people, in [execution-plan.md](architecture/execution-plan.md) (V21)
