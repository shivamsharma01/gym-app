---
applyTo: "backend/**"
---

Backend is the canonical business reconciliation authority.

All member identity and conflict decisions belong here.

Reader state is observed state / projection.

The backend determines desired state.

## Stack

Java 21, Spring Boot 4.1, one modular service. MySQL 8.4 with Flyway; no
Hibernate auto-DDL in production. Numeric primary keys stay internal;
`publicId` (immutable UUID) is the external id. Scope every query by
tenant; use foreign keys, optimistic locking, and indexes that match real
queries. No native SDK code in the backend.

## Model (names may differ, semantics may not)

- Member: `publicId`, memberCode, profile, deviceRole USER/ADMIN,
  accessAllowed, archive status, face reference.
- Membership: start/end dates, plan/payment state.
- Device: tenant, gateway, IN/OUT direction.
- MemberDeviceMapping: the only link from `(deviceId, deviceUserId)` to a
  member, with status and history. One mapping per reader.
- DesiredDeviceState: per-reader desired revision, deviceUserId, effective
  validity, frozen state, authority, face hash, desired presence.
- DeviceObservedState: last acknowledged reader snapshot plus face hash,
  observedAt, source, snapshot hash.
- DeviceProjectionRevision: monotonic per reader, with applied ack.
- SyncReviewItem: baseline, server state, per-device observations,
  evidence, suggested matches, resolution, actor.
- PendingDeviceEnrollment: reader-created person not yet a member.
- AttendanceEvent: append-only; reader, recNo, time, allowed/denied,
  method, raw error, resolved member when known.

## Access

Effective door access = accessAllowed AND membership active AND not
archived. Disable keeps the device user and freezes it. Remove from reader
sets desired presence false and never deletes the member. Do not claim
disable blocks the door until POC P1 says so.

## Reconciliation (whole records)

| Baseline vs server | Baseline vs device | Outcome |
| --- | --- | --- |
| same | same | no-op |
| same | different | device changed: review / change request, never silent overwrite |
| different | same | server changed: project to device and verify |
| different | different, S = D | converged: update baseline, audit |
| different | different, S != D | true conflict: review item, no winner |
| no baseline | any | bootstrap / enrollment / drift: mapping or review, no auto-merge |

- A device deletion is drift; the member stays. Only an admin decision
  changes canonical state.
- Pending enrollments: admin creates, links or rejects. Suggest matches by
  evidence; never auto-merge identity.
- Bootstrap has no master reader. Ambiguous identities go to the review
  queue. Never guess a match.

## Desired-state publishing

- A business change and the projection revisions it produces are written
  in the same transaction.
- Notify the gateway with a lightweight wake-up; the gateway pulls changes
  after its applied revision in bounded pages.
- A newer revision supersedes older ones; stale revisions can never roll
  back state. Duplicate commands and events are no-ops.
- Member changes are desired revisions for one reader. The gateway does
  not fan an observation out to other readers.

## Gateway trust

A gym has one gateway. It authenticates with an enrollment token, then a
rotating credential, and sees only its own gym. Never take gateway identity
from an unauthenticated payload.

## Attendance

Append-only, separate from member reconciliation. Resolve members through
the mapping; keep the raw device user id. `(deviceId, recNo)` is a
candidate idempotency key, not proven. Missing IN/OUT is not a sync error.

## Face and audit

Store one resized JPEG per member, encrypted at rest, so a reset or
replaced reader can be rebuilt. Never log face bytes. Audit every
resolution with actor, prior state, chosen state and resulting revisions.
