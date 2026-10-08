# Sync timing and performance backlog

Estimates for a gym with about 1,200 members and two TrueFace readers. They come from reading the code and
from the sync logs of October 2026 on the local network. They are not measured end-to-end runs; check them
against the logs of the next real setup.

## Inputs behind the estimates

- **Server sending rate:** the outbox sends at most 50 commands every 10 seconds (300 a minute).
- **Commands per member when a reader is new to the server:** about 3 per reader (create, access dates,
  photo). Two new readers with 1,200 members means about 7,200 commands, so at least 24 minutes of sending
  even when most of them change nothing on the reader.
- **Reader list:** about 3 seconds to list all users of one reader.
- **Photo reads:** about 0.1 s per photo on 192.168.31.92 and 0.25 s on 192.168.31.91, read one reader at a
  time in batches of 40. That is 2–5 minutes per reader for 1,200 photos.
- **Copying a user to the other reader:** about 1–1.5 s per user (create, read photo, write photo).
- **No-op checks on the gateway:** about 25–100 ms per user command when the reader already holds the data.

## Scenarios

| Scenario | Approximate time to "server and both readers hold the same data" |
|---|---|
| New gateway, server empty, both readers already hold the same users | 10–15 min |
| New gateway, server and both readers already hold the same users | 25–40 min |
| New gateway, server holds the members, one reader full and the other empty | 30–45 min |
| New gateway, both readers hold users but with differing data | Between the two rows above, depending on how many users differ |
| Any of the above on a slow network (photo reads of about 4 s, as in the first import log) | 2–3 hours |
| Gateway restart or reconnect after a completed sync, nothing changed | 1–2 min |
| Gateway restart or reconnect after changes made while it was offline | 1–2 min plus about 1–1.5 s per changed user |
| Regular 15-minute reconcile, nothing changed (checksum matches) | Seconds; the reader list is not sent |
| Daily full comparison or Sync Now | About 3 s per reader on the gateway plus about 1,200 lookups per reader on the server |

Notes:

- **Why the "already the same" case still takes 25–40 minutes:** the server queues its commands for every
  member on both readers and sends them at 300 a minute. Most of them change nothing, but the pacing still
  applies. Item 3 below is the fix.
- **One reader with data takes longer than both:** every user has to be written to the empty reader, and
  each write includes a photo copy.
- **Restarts don't repeat the setup:** the gateway keeps each reader's user and photo record, the member copy
  and unsent reports on disk, so only real changes are replayed.
- **The checksum (since migration V26):** an unchanged reader skips the full list on the regular reconcile.
  A full comparison still runs at least once a day per reader, after any user command the server sends, and
  on Sync Now.

## Open improvements

### 1. Setup step on gateway connect checks every member against every reader

- **Where:** `GatewayConnectedListener.onGatewayConnected` calls
  `MemberDeviceProvisioningService.provisionDevice` for every reader of the gateway. It loops over all active
  members and runs `ensureMapping` for each.
- **Cost:** about 2 small database queries per member per reader. For 1,200 members and 2 readers that is
  about 4,800 queries on every connect, even when no member is missing. Earlier notes rounded the total
  reconnect cost (this step plus the reconcile lookups) to about 5,000–7,000 small queries; the reconcile
  part is now mostly avoided by the checksum.
- **Fix:** one query that finds active members with no mapping on that reader (an anti-join), and run the
  per-member logic only for those. Alternatively run the backfill only when a reader is assigned to a
  gateway, and not on every connect.
- **Result:** about 4,800 queries per reconnect become 2 (one per reader).

### 2. Full reconcile after short reconnects

- **Where:** `GatewayConnectedListener` queues a reconcile for every reader on each connect
  (`enqueueReconcileIfAbsent`), as do `AutoReconcileScheduler` every 15 minutes and `GatewayMessageService`
  when a reader comes back online.
- **Cost today:** with the checksum, an unchanged reader costs one reader list on the gym PC (about 3 s) and
  a small message. The server-side comparison is skipped. What remains is the attendance pull and the queued
  command per reader.
- **Fix:** if the gateway was offline for less than a few hours and its saved state is intact, rely on the
  queued changes and the regular schedule instead of queueing a reconcile right away. Move the daily full
  comparison to off-hours with a random delay per gym, so hundreds of gyms don't run it at the same moment
  (for example when gym PCs start around 6 am).
- **Result:** fewer reconnect bursts across tenants. This matters mainly once there are many gyms.

### 3. Faster first-time setup

- **Where:** `OutboxDispatcher` sends one batch of 50 every 10 seconds (`app.gateway.outbox.dispatch-interval-ms`).
- **Cost:** the 24-minute floor for setting up two new readers with 1,200 members, even when nothing changes
  on the readers.
- **Fix:** send the next batch for a gateway as soon as it finishes the previous one (acknowledgement-driven),
  with a limit on commands in flight for the gym's gateway instead of a fixed timer. Alternatively, when a reader is new
  to the server, run a reconcile first and queue commands only for users the reader is missing or holds
  differently.
- **Result:** the "already the same" setup would drop from 25–40 minutes towards the 10–15 minute case.
