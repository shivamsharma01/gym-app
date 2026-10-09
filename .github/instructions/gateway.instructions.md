---
applyTo: "gateway/**"
---

The gateway is an edge controller, not a business decision engine.

It may:
- connect to devices
- read device state
- persist observations
- apply desired revisions
- retry
- reconnect
- buffer attendance

It must not:
- decide member identity
- resolve business conflicts
- choose which device wins
- use timestamps to resolve member conflicts
- silently merge device users
- make backend business-policy decisions

## Runtime

- C# on .NET 10. The gateway is the only process that calls the native
  SDK; use the existing NetSDK binding (`Gym.Gateway.NetSdk`). Do not
  rebind the SDK.
- All reader access goes through the adapter interface, with mock,
  simulator and TrueFace implementations. Mock and simulator must cover
  online/offline, events, denied access, delayed or failed acks,
  duplicates and gap recovery, so work does not need hardware.
- Cloud link is an authenticated outbound WebSocket (REST fallback for
  ingest). Durable retry with backoff; no in-memory queue, no sleep loop.
  No connection without a credential.

## Desired-state execution

1. Receive a "desired revision available" wake-up.
2. Pull desired changes after the saved applied revision, in bounded pages.
3. Apply only actual differences. User updates are full logical-record
   writes; face is a separate operation.
4. Verify by read-back (or POC-proven SDK confirmation) before claiming
   success.
5. Ack the highest fully applied revision per reader.
6. Ignore stale revisions; a newer revision supersedes older ones.

A failed face write means the projection is not fully applied. Retry and
surface the error.

## Reconnect order

authenticate -> re-establish reader health -> collect current reader
observations -> send observations/cursors to server -> pull desired
revisions -> apply/verify -> ack.

Never replay stale desired commands before observing the reader.

## Local persistence

One small SQLite journal replaces the JSON stores. It is durability, not a
second business brain. It holds reader snapshots, the desired applied
cursor per reader, the outbound event journal, provisional enrollments,
the attendance cursor and retry metadata.

- Do not persist heartbeats as replayable events; health is in-memory
  state plus telemetry.
- One worker / connection owner per reader. No shared mutable dictionaries
  or overlapping roster reads and writes.
- Bound every queue. Business events (enrollment, attendance) have explicit
  retention and alerting.
- A failed reader operation must not block other readers or backend
  delivery.

## Detecting reader changes

- Alarm with a user id: targeted read of that user (and face if needed).
- Alarm without a user id: coalesced full roster scan for that reader.
  Never two roster scans at once on one reader.
- Periodic safety scan at a low, staggered, benchmarked interval. Not
  15-second polling.
- Reconnect: one full observation before any desired-state replay.

Alarms are scan triggers, not authoritative change records. Keep a
normalized snapshot hash and per-user fingerprint; compare locally and send
only changed or unresolved items upstream. Read faces only when needed.

`stuUpdateTime` is telemetry only. Never use it to order changes.

## Offline reader changes

- A reader-created user keeps its reader `szUserID`. Store a durable
  observation with the full record and face, then report it on reconnect
  as a pending enrollment. Do not create a member. Do not copy that
  person onto another reader.
- An occupied device user id is reported. Do not overwrite the user who
  already has it, and do not invent a replacement id in the gateway.
- Reader-side edits, administration changes, and deletions are
  observations. If two readers changed the same member differently from
  the same baseline, keep both. Do not write one reader's values onto
  the other.

## Attendance

- Query by a bounded time window. Do not query for records after a
  record number. The 7 October 2026 run did not show that query.
- Idempotency is device id, record number, and the stored timestamp.
  Record number alone is not the key: survival across reboot or a full
  log is unproven.
- Keep the raw device user id even when no mapping exists.
- Attendance ingestion must never hold up member reconciliation.

## SDK facts

Only the UNKNOWN list in `.github/copilot-instructions.md` is unverified;
do not assume answers to it. Hardware experiments live in
`gateway/tools/Gym.Gateway.SdkProbe` (run `--gates`; see GATES.md) and
must not touch production code or existing gym users.

Never log face bytes or device passwords.
