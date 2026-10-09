# Open questions

Architecture: [gym-device-sync-before-poc-architecture.md](gym-device-sync-before-poc-architecture.md).
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
- Deleting a member's face photo removes that photo from the server and from every reader that already has the member. The member and the mapping stay. A reader that does not already have the member is not given a user. Creating a member who has never had a photo is still open.
- Gateway authentication is the credential. Anonymous access is not part of the design. F1 records that the automated tests passed.

## Still open

1. Which readers receive a new member: every reader of the gym, a branch, or a reader chosen per member? V1 is one reader chosen at create time.
2. Who may approve review items and pending enrollments, and does a pending enrollment expire?
3. Keep the authenticated HTTP poll beside the WebSocket, or use the WebSocket only? Either way the caller is the enrolled gateway.
4. When to schedule the attendance poll. The shape is a time window and append-only rows. Record-number survival across reboot or a full log is unproven, and there is no measured retention cap. The poll must not write member state.
5. Whether a destructive clear of the reader log is ever offered. It is not part of member sync.
6. Whether an unlinked person may be copied to another reader while the server is unreachable. The current release does not do that.
7. The safety-scan interval. The only roster measurement is about 1,200 users in 3–5 seconds. Do not hardcode 15 seconds from an unmeasured larger roster.
8. Create-without-a-face, door and time-section values other than the probe's `nDoorNum=1` and `nTimeSectionNum=1`, and SDK reconnect as a guaranteed behavior. One power cycle reconnected and one did not.

## Operational risks that remain

- A user write that omits validity can zero dates on this firmware. Desired user writes include the full validity window.
- Hash the face bytes read back. This firmware stored some padded files as a different image.
- `0x800004B5` alone is not "user missing." The fail code separates a missing photo from a missing user.
- There is no query for attendance records after a record number. Poll a time window.
- Live `ALARM_ACCESS_CTL_EVENT` did not arrive for the punches in the 7 October 2026 run. Do not use it as the attendance source.
