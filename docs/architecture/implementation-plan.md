# Implementation plan

Architecture: [gym-device-sync-before-poc-architecture.md](gym-device-sync-before-poc-architecture.md).
Slice record: [execution-plan.md](execution-plan.md).
Open questions: [open-questions.md](open-questions.md).
Evidence: [device-poc-results.md](device-poc-results.md), run `sync-gates-20261007-130047`, reader serial `TW30000005250265`.

This is the build of the desired-state system. There is no second member-sync design beside it.

## What the system is

- One gym has one gateway. The gateway's identity comes from its credential.
- The backend owns members, desired projections, revisions, review, and audit.
- Each reader has its own worker. That worker applies desired revisions, reads the result back, and acknowledges only a verified revision.
- Reader-originated people and edits are observations. Staff decide. The decision becomes a new desired revision and stays open until the reader verifies it.
- A trusted empty roster may be seeded from server members. A failed, short, or count-mismatched list seeds nothing and deletes nothing.
- Attendance is a separate time-window poll. It does not write member state.
- A bad release is recovered with the previous build and a database backup. Pending revisions, acknowledgements, and review items are data in that backup. They are not a reason to keep another sync design.

## What is done

F1–F3 and V1–V16 in the execution plan. Automated tests for those slices passed on the fake reader and the test database.

## What is not done

- Section E of the execution plan: physical confirmation of create, face read-back, freeze and enable, validity, face replace, and reconnect.
- Attendance ingestion as specified in the architecture. It stays isolated from member revisions.
- The open product decisions in [open-questions.md](open-questions.md).

The gateway worker runs on Windows. A physical Linux login to a reader has not been run.
