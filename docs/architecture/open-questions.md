# Open questions

Architecture: [device-sync-architecture.md](device-sync-architecture.md).
Evidence: [device-poc-results.md](device-poc-results.md).
Slice record: [execution-plan.md](execution-plan.md).

These are product or hardware questions the architecture does not close. Do not invent an answer in code or in another document.

## Closed by measurement or by the execution plan

- Freeze (`nUserStatus=1`) blocked the door on this reader and kept the face (P1 in the results). Enable restored access. That is one unit, one run.
- The reader clock used for validity stays on India local time. Punch `stuTime` is UTC. Do not call `SynchronizeTime` to move this reader to UTC.
- Device administration is the reader password. Do not project `emAuthority=Administrators` as menu permission.
- The gateway process that calls the SDK is the Windows build.
- A person created on the reader keeps that reader's device user id. The server does not rewrite it. Server-created ids use the highest-known-plus-one rule in the execution plan. A live race against the screen was not run.
- A short or empty list is not a disappearance. This unit was not factory-reset in place.
- Equal face bytes on two ids are two observations. They are not one member.
- Deleting a member's face photo removes that photo from the server and from every reader that already has the member. The member and the mapping stay. A reader that does not already have the member is not given a user.
- A member is not created without a face photo.
- A new member is published to every reader of the gym. The create screen does not ask for a reader.
- Gym admin and staff may decide a review item. A pending enrollment of a person on a reader does not expire. The one-time gateway enrollment token does expire (24 hours) and is consumed on use.
- The WebSocket carries live gateway traffic. The HTTP command poll runs only while that socket is down, so a dropped socket still delivers door and clock commands.
- Attendance should reach admins as soon as the punches exist, by a poll that does not repeat work while nothing has changed. The reader log is not cleared. V19 builds this as a gateway-owned time-window poll; the server no longer schedules attendance reads.
- **Server is the only brain.** An unlinked person is never copied to another reader by the gateway (online or offline). Desired state comes from the server only. Offline gateway-side copy is out of scope permanently, not deferred.
- Gateway authentication is the credential. Anonymous access is not part of the design. F1 records that the automated tests passed.
- **Safety-scan interval:** default every 5 minutes (`RosterPollSeconds` = 300). Measured ~1,200-user list is a few seconds. Product rule by typical duration: under ~30s → every 5 minutes; 30s–2 minutes → hourly; longer → daily. Staff can still force a scan (`SCAN_READER`).
- **SDK reconnect as a process guarantee:** the Windows gateway runs as a start-on-restart service. V6 re-reads and pulls after the process is up; it does not depend on the old SDK login surviving a power cut. (POC P20: auto-reconnect of an old login was UNKNOWN / mixed.)
- **Unreadable attendance `stuTime`:** skip that one messy row, keep valid punches in the same window, log a warning with the skip count. Do not invent a substitute time. Search/page failures still fail the whole read.

## Still open

1. **Attendance record-number survival across reboot or a full log, and retention cap.** Oct 7 POC (P6): record numbers rose during the afternoon; the two power cycles did **not** give a clean before/after punch that proves `nRecNo` survives reboot, and the full-log wrap case was not run. V19 does not need this for correctness (time-window poll + fingerprint), so it stays unproven hardware detail, not a product blocker.
2. **Roster scan duration on production hardware.** If a site’s full list pass grows into the 30s–2 minute band (or beyond), raise `RosterPollSeconds` to hourly or daily per the rule above. Test DB: ~1,200 users ≈ a few seconds for the list / ~8.5 s acknowledged list-only scan.

## Operational risks that remain

- A user write that omits validity can zero dates on this firmware. Desired user writes include the full validity window.
- Hash the face bytes read back. This firmware stored some padded files as a different image.
- `0x800004B5` alone is not "user missing." The fail code separates a missing photo from a missing user.
- There is no query for attendance records after a record number. Poll a time window.
