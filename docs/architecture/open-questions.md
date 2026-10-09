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
- A new member is published to every reader of the gym. The create screen still asks for one reader until that fan-out is built.
- Gym admin and staff may decide a review item. A pending enrollment of a person on a reader does not expire. The one-time gateway enrollment token does expire (24 hours) and is consumed on use.
- The WebSocket carries live gateway traffic. The HTTP command poll runs only while that socket is down, so a dropped socket still delivers door, clock, and reconcile commands.
- Attendance should reach admins as soon as the punches exist, by a poll that does not repeat work while nothing has changed. The reader log is not cleared.
- An unlinked person is not copied to another reader while the server is down. The server publishes desired state. The gateway does not resolve that conflict on its own.
- Gateway authentication is the credential. Anonymous access is not part of the design. F1 records that the automated tests passed.

## Still open

1. The safety-scan interval. A safety scan is an occasional full read of one reader's user list, so a change made on the reader is still noticed when its alarm was missed. It is not the normal way changes are detected, and it is not the attendance poll. The only measurement is about 1,200 users in 3–5 seconds. Do not hardcode 15 seconds from an unmeasured larger roster.
2. Door and time-section values other than the probe's `nDoorNum=1` and `nTimeSectionNum=1`.
3. SDK reconnect as a guaranteed behavior. One power cycle reconnected and one did not.
4. Attendance record-number survival across reboot or a full log, and the reader's retention cap. The poll is a time window of append-only rows and must not write member state.

## Operational risks that remain

- A user write that omits validity can zero dates on this firmware. Desired user writes include the full validity window.
- Hash the face bytes read back. This firmware stored some padded files as a different image.
- `0x800004B5` alone is not "user missing." The fail code separates a missing photo from a missing user.
- There is no query for attendance records after a record number. Poll a time window.
- Live `ALARM_ACCESS_CTL_EVENT` did not arrive for the punches in the 7 October 2026 run. Do not use it as the attendance source.
