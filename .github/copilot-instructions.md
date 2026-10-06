This repository is being migrated to the architecture documented at:

@docs/architecture/gym-device-sync-before-poc-architecture.md

This architecture is authoritative.

Do not preserve existing synchronization behaviour merely because
the current implementation already does it.

Do not introduce a second conflict-resolution brain in the gateway.

The backend is the business reconciliation authority.

deviceUserId is device-local.

publicId is member identity.

member_device_mapping is the relationship between them.

Never use deviceUserId as a tenant-wide member identity.

Do not use timestamps as a conflict-resolution mechanism.

Do not invent unsupported device capabilities.

Any unverified device behaviour must be marked UNKNOWN and must not
be converted into an implementation assumption.

Before modifying a synchronization workflow, identify:
1. desired state
2. observed state
3. durable state
4. idempotency behaviour
5. retry behaviour
6. failure behaviour
7. verification behaviour

## Mental model

server desired state + reader observed state + last reconciled baseline
= deterministic reconciliation.

Reconnects, restarts, reader replacement, offline enrollment, duplicate
commands and device drift are variations of this one problem. Do not
add special-case algorithms for them.

## Responsibilities

- Backend: canonical member state, memberships, accessAllowed,
  deviceRole, device mappings, desired per-reader projection, revision
  ordering, conflict/review outcome, audit.
- Gateway: reader connections, snapshots, local journal, execution,
  retry, attendance buffering, reconnect, mechanical propagation of
  safe local work. Never a business winner or identity merger.
- Reader: local enforcement, local user/face storage, local admin
  operations, attendance log. Never server identity.
- React UI: admin actions and review decisions through server APIs only.

## Invariants

- Identity: `(deviceId, deviceUserId)` identifies a device-side record.
  Never join `deviceUserId == memberCode`, `deviceUserId == serialNumber`,
  or "same integer across tenant". Resolve through `member_device_mapping`.
- Reader edits are observations / change requests, not automatic truth.
- Conflicts use three-way comparison of baseline, server desired and
  device observed, on whole records. Field-level merge is deferred.
  A true conflict becomes a review item; no automatic winner.
- Every user write sends the full logical record. Face is a separate
  operation through the face path. The SDK may zero omitted fields.
- Freeze/disable keeps the device user (`nUserStatus=1`). Removal deletes
  the device record. They are different operations.
- A device-side deletion never deletes the server member.
- No permanent master reader. Bootstrap is a reconciliation phase.
- On reconnect, observe the reader before applying desired state. Never
  blindly replay stale commands.
- A newer desired revision supersedes older ones. Do not replay every
  intermediate business edit.
- Final convergence: once everything is connected and reviews are
  resolved, every reader is semantically equal to the server desired state.
- Attendance is append-only and outside member reconciliation. It must
  never block member sync.
- Every inbound/outbound business message is idempotent or revision-scoped.

## Security

- Gateway authentication is mandatory. No anonymous fallback. Never bind
  gateway identity from an unauthenticated payload.
- A gateway receives only its own tenant/gym projections.
- Face data is encrypted at rest and never written to logs.
- Never commit device passwords, tokens, or face images.
- Audit every resolution: actor, source observations, chosen state,
  resulting revision.

## Hardware facts that are still UNKNOWN (POC gates, section 18)

Do not encode these as invariants until the POC records an answer:

- P1 whether `nUserStatus=1` actually blocks the door (release gate).
- P2 how the reader screen chooses `szUserID`.
- P3 whether INSERT over an existing user zeroes omitted fields.
- P4 whether `stuUpdateTime` changes on every mutation. Never use it for
  conflict ordering.
- P5 whether alarms fire reliably and what they carry. Observed codes are
  0x3240, 0x3216, 0x3218, 0x34C2; 0x3491 is not a reliable "user changed"
  signal. Alarms are scan triggers, not change records.
- P6/P7 whether `nRecNo` is monotonic and persistent, and whether
  attendance can be queried after a record number. Use time windows.
- P8 attendance retention, P9 same face on two ids, P10 safe roster size
  and SDK concurrency, P11 validity boundary and timezone, P12 factory
  reset signals.

The POC harness is `gateway/tools/Gym.Gateway.SdkProbe --gates`
(see its GATES.md).

## Retired; do not reintroduce

- Permanent master device and "copy master roster".
- Timestamp / latest-wins conflict logic.
- Device-ID-as-serial fallback.
- Gateway and backend both fanning out writes.
- `updatedByGateway` as the consistency mechanism.
- 15-second full-roster polling as the change-detection method.
- Heartbeats persisted as replayable business events.
- Per-member command outbox as the consistency model.
- iAS as a runtime dependency or second source of truth.
- A Java/JNA gateway.
