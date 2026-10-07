# Device POC results — 7 October 2026

Source run: `sync-gates-20261007-130047` (`report.txt`, `gates.csv`).
Harness: `gateway/tools/Gym.Gateway.SdkProbe` `--gates`.
Questions: section 18 of [gym-device-sync-before-poc-architecture.md](gym-device-sync-before-poc-architecture.md) (P1–P12) and the extra checks P13–P21 in [GATES.md](../../gateway/tools/Gym.Gateway.SdkProbe/GATES.md).

This is one gym reader, one run, two trials where the harness asks for two. A verdict here is what that run showed. It is not a firmware guarantee.

## How to read a verdict

| Verdict | Meaning in this run |
| --- | --- |
| OBSERVED | The behavior in the question was seen, the same way on every trial that counted. |
| NOT OBSERVED | The opposite was seen, the same way on every trial that counted. |
| UNKNOWN | A step was skipped, a read failed, only one trial counted, or the trials disagreed. |

The harness does not upgrade a messy result into an answer. Where the recorded verdict and the useful reading of the evidence differ, both are stated below. The clock section is the one to read before P6 and P18.

## Run

| | |
| --- | --- |
| When | 2026-10-07 13:00:48 India Standard Time (07:30:48 UTC), machine `HR13GYM` |
| Reader | `192.168.31.91:37777`, serial `TW30000005250265`, type `NET_BSC_SERIAL` |
| Reader clock at start | 2026-10-07 13:00:49, matching the PC's local clock |
| Roster at start and after cleanup | 1209 users. Cleanup removed the test users. The list matched the start. |
| Test users | `990001` (walks and writes), `990002` (duplicate face), `990003` (delete). Screen users `1210` and `1211`. |
| Not in this run | A full attendance log (`--spare-reader` was not used). The four P19 face-menu tries were skipped; the operator later described the menu from the device itself. The gateway stays on Windows, so a Linux login was not part of this POC. |

Event listening was on for the whole run. Punches for the test user were stored in the attendance log. No `ALARM_ACCESS_CTL_EVENT` arrived for any of them.

## Scoreboard

| ID | Verdict | What the run settled |
| --- | --- | --- |
| P1 | OBSERVED | `nUserStatus=1` keeps the door shut. `0` lets the same face in. |
| P2 | OBSERVED | The screen offered the smallest free numeric id, twice (`1210`, then `1211`). |
| P3 | NOT OBSERVED | A name-only insert keeps the face and clears both validity times. |
| P4 | UNKNOWN | `stuUpdateTime` stayed zero on every read. It did not move, so it cannot order changes. The harness leaves this UNKNOWN because a zero that stays zero is not a measured change. |
| P5 | NOT OBSERVED | SDK edits of validity, freeze, authority, face, and delete raised no alarm on either trial. |
| P6 | NOT OBSERVED | Record numbers rose during the run. The time-window part failed the harness check. Reboot and full-log parts are UNKNOWN. See the clock section before treating the window failure as a broken filter. |
| P7 | NOT OBSERVED | The SDK find struct has no "after record number" bound. Inside a time window, ascending and descending record-number order both worked. |
| P8 | UNKNOWN | The log reports 124,389 records. Record 1 is still present, dated 2025-10-12. The read stopped at 50,000 rows, and a full log was not tested. |
| P9 | OBSERVED | The same photo is accepted on two user ids. Both read back identical. |
| P10 | UNKNOWN | One sample: about 1,200 users list in 3–5 s. Not a safe concurrency limit. |
| P11 | OBSERVED | Dates store as sent. A window that includes today opens the door. A window that starts tomorrow keeps it shut. |
| P12 | Closed without a reset | This reader cannot be factory-reset. There is no reset signal to discover. Replacement of the whole unit is the empty-reader case. |
| P13 | NOT OBSERVED | Eight walks stored punches. None produced `ALARM_ACCESS_CTL_EVENT`. |
| P14 | OBSERVED | A 44 KB photo reads back byte for byte. A second INSERT fails with `PHOTO_EXIST`. An UPDATE with no photo leaves the stored photo in place. |
| P15 | OBSERVED | Missing photo and missing user share SDK error `0x800004B5`. The fail code differs. Removing a missing user or photo returns success. |
| P16 | OBSERVED | Two idle reads each returned the announced 1,212 users, the same id set, no duplicates. |
| P17 | OBSERVED | `szNameEx` stores 127 characters. `szName` stores 31. The screen showed `szName`. Writing `szName` alone with `bUseNameEx=false` clears `szNameEx`, and the flag still reads back true. |
| P18 | NOT OBSERVED | Every stored punch time is 5 h 30 min behind the reader clock. That is UTC against an India-local device clock. |
| P19 | Closed by the operator | The setup menu is available to every person. It then asks for the reader credentials. `emAuthority` does not decide who may add or edit users. |
| P20 | UNKNOWN | One power cycle did not reconnect the old login. The next one did. Live door events could not be judged. |
| P21 | OBSERVED | 100, 130, and 200 KB padded JPEGs were all accepted. Each was stored as the same 17 KB re-encode, not the bytes that were sent. |

Nine OBSERVED, six NOT OBSERVED, six UNKNOWN.

## The reader has two clocks

This single fact explains P18 and the P6 time-window verdict.

`QueryDeviceTime` returned India local time. At 13:05:24 on the reader, the PC was 13:05:25 India Standard Time and 07:35:25 UTC.

Every punch `stuTime` in this run is that same instant written as UTC. The first walk is the pattern for all of them:

| | |
| --- | --- |
| Reader clock during the walk | 13:00:55–13:02:13 |
| Stored punch | 2026-10-07 07:31:54 |
| UTC plus 5 h 30 min | 13:01:54, inside the walk |

The same offset holds for every later walk, including the denied ones. The harness question for P18 is whether the stored time is the reader's own clock. It is not. The verdict NOT OBSERVED is the right record of that.

What the run does not show is a broken attendance clock. The punches line up with the walks once 5 h 30 min is added. Membership dates in P11 were also sent and stored as reader-local wall times (`2026-10-07 23:59:59` for end of day), and the door obeyed those dates. The device clock used for validity is local. The punch timestamp is UTC.

The current gateway assumption that the reader runs in UTC (migration assessment C17) does not match this reader. This run did not call `SynchronizeTime`, so it did not move the clock. A later sync that sets the reader to UTC would be a different device state from the one measured here.

## P1 — freeze blocks the door

OBSERVED.

| Walk | Operator | Stored punch |
| --- | --- | --- |
| Enabled, before freeze | Opened | 124365 granted, `FACE_RECOGNITION` |
| Frozen (`nUserStatus` read back 1), first walk | Shut | no punch |
| Frozen, second walk | Shut | 124367–124369 denied, error `0xA4`, `FACE_RECOGNITION` |
| Enabled again (`nUserStatus` read back 0) | Opened | 124370–124372 granted |

The first frozen walk produced no punch and a shut door. The second produced denied punches and a shut door. Both enabled walks opened. Freeze is a usable way to stop entry without deleting the user or the face.

## P11 — validity is enforced on the reader-local date

OBSERVED. Sent dates were stored unchanged.

| Window | Stored | Door | Punch |
| --- | --- | --- | --- |
| 2026-10-06 00:00:00 through 2026-10-07 23:59:59 | identical | Opened, twice | granted |
| 2026-10-08 00:00:00 through 2026-11-06 23:59:59 | identical | Shut, twice | denied, error `0x14` |

"Today" and "tomorrow" were the reader's date, 2026-10-07. A membership window written in UTC end-of-day would not match the boundary this reader enforced.

## P3 — a partial user insert clears validity

NOT OBSERVED, on both trials. The insert wrote the id and a new name over the existing test user.

Kept: the face.

Cleared: `stuValidBeginTime` and `stuValidEndTime`, both to zero. `szName` and `szNameEx` both became the new name.

A name patch is not a safe update. A user write has to send the validity window again, or this firmware will open the membership into an empty range. The face is a separate record and survived.

## P14, P15, P21 — face bytes, missing records, and size

P14 is OBSERVED for the walker's real file (44 KB, MD5 `4A89CE6D4357B7369B762C2061E04A8F`):

- INSERT, then two reads: same length, same MD5.
- INSERT while a photo already exists: `FAILED 800004B5`, fail code `PHOTO_EXIST`, both trials.
- UPDATE with no photo: the call returns ok, and the previous photo is still there, same MD5.

Replacing a face cannot be a second INSERT. An empty UPDATE is not a delete.

P15 is OBSERVED and the same SDK error means two different things:

| Call | Result | Fail code | SDK error |
| --- | --- | --- | --- |
| GET photo when the user has none | failure, no photo | `UNKNOWN` | `0x800004B5` |
| GET user after the user was removed | failure, empty id | `NO_RECORD` | `0x800004B5` |
| REMOVE photo that is already absent | ok | | |
| REMOVE user, including a second time | ok | | |

`0x800004B5` alone cannot be treated as "this user has no photo." The fail code is what separates a missing photo from a missing user. REMOVE of something already gone succeeds, so a successful remove is not proof that a record existed.

P21 is OBSERVED, with a narrower meaning than "the reader stores what you sent." The harness pads the same JPEG with comment blocks and inserts it. All six writes (100, 130, and 200 KB, two each) returned ok. Every read-back was 17 KB with MD5 `6D95D5E72A6A333E5A87CF8BC06F4A02`. The reader accepted payloads the gateway currently refuses above 120 KB, then stored one re-encoded image, not the padded file. The 44 KB original in P14 was stored byte for byte, so this firmware does not re-encode every photo. It did re-encode these padded files. A hash of the file the gateway sent will not match a later read when the reader re-encodes.

## P9 — one face, two user ids

OBSERVED. INSERT of A's photo onto B succeeded twice. Both users read back 44 KB and the same MD5. The reader did not reject the duplicate. B was then removed. Offline enrollment and migration cannot assume the device keeps faces unique.

## P2 — screen ids

OBSERVED, with the harness limit: two samples, not a proof for every future id.

The operator kept the id the screen suggested. Trial 1 created `SYNCPOC SCREEN 1` as `1210`. Trial 2 created `SYNCPOC SCREEN 2` as `1211`. Both fit "smallest free numeric id," and neither was "highest plus one" (the test user `990001` was already on the reader). A server-chosen id in a high range is not what this screen allocates while low ids are free. A collision policy still has to allow for the screen filling holes.

## P17 — names

OBSERVED on both trials.

- A 127-character `szNameEx` was stored in full. The 31-character `szName` written beside it came back 31 characters and not equal to the string that was sent. `bUseNameEx` read back true.
- A later write of `szName` only, with `bUseNameEx=false`, stored that short name in full (15 characters) and did not keep `szNameEx`. The flag still read back true.
- After that plain write, the reader screen showed the `szName` value.

The screen name and the long SDK name are different fields. Clearing or omitting `szNameEx` drops the long name even when the caller sets `bUseNameEx=false`.

## P4 and P5 — no change clock, no reliable edit alarm

P4 is UNKNOWN in the scoreboard. The evidence is still uniform. For name, validity, freeze, authority, and face replace, both trials read `stuUpdateTime` as zero before the write and zero after it. Photo time was zero as well. Delete cannot be judged, because there is no user left to read. On this firmware `stuUpdateTime` is not a change sequencer. Ordering edits by it would treat every edit as "no time."

P5 is NOT OBSERVED for the SDK mutations that matter to sync. Validity, freeze, authority, face replace, and delete produced no alarm on either trial. Name produced a single `0x3491` on the second trial only.

Screen trials are UNKNOWN because the alarm sets did not match across the two trials. The name change itself was real (`SYNCPOC A` became `SYNCPOC name changed`, then `SYN`), and both screen face enrolments changed the photo MD5. Around those steps the probe also recorded `EVENT_MOTIONDETECT`, `ALARM_ACCESS_CTL_STATUS`, `0x3491`, and `0x3475`. Motion events are someone standing at the reader. They are not a user-edit signal. `0x3475` showed up on some screen edits and on one screen create, including a payload that contains the user id, and it was absent on other trials of the same action.

An alarm is not a complete list of reader edits. A scan still has to read the users.

## P16 and P10 — listing the roster

P16 is OBSERVED. With nobody editing on the screen, two full reads each returned 1,212 users, matching the announced total, with the same ids and no duplicate ids. Page capacity reported was 1,000; the read took 25 calls. A list that matches the announced total is repeatable on an idle reader. A short or failed list is still not evidence of deletions; this run never produced one.

P10 is UNKNOWN by design (one sample). The numbers are still the baseline for this roster:

| Call | Result |
| --- | --- |
| Full list, 1,209 users | 4.8 s, 25 calls of 50 |
| Full list again, 1,212 users | 3.1 s, 25 calls |
| `GetUser` ×5 | 20–24 ms, no failures |
| `GetFace` ×5 | 239–276 ms, no failures |
| `GetUser` and `GetFace` overlapped once | both ok (24 ms and 271 ms) |

Face reads dominate. One overlapped pair succeeded. That does not set a parallel-call limit.

## Attendance: P7, P8, P13, P6, P18

### P7 — no record-number cursor

NOT OBSERVED, and that is the answer the architecture needs. `NET_FIND_RECORD_ACCESSCTLCARDREC_CONDITION_EX` can bound a search by card number, by a time window, or by a RealUTC window, and it can ask for a sort. It has no field for "records after this `nRecNo`." That was read from the SDK struct.

Inside a time window the reader did honor record-number order, both ascending and descending. The last-24-hour query returned 498 punches in 1.9 s. Backfill has to walk time windows. It can sort by record number inside a window. It cannot ask the reader for "everything after cursor N."

### P8 — how far back the log still goes

UNKNOWN for the retention policy. The facts the read did establish:

| | |
| --- | --- |
| Records the reader says it holds | 124,389 |
| Rows downloaded | 50,000 in 197 s, then the `--log-cap` stopped the read |
| Oldest row in that download | record 1 at 2025-10-12 09:11:17 |
| Newest row in that download | record 50,000 at 2026-03-20 15:27:09 |

The download walked record numbers upward from 1. Today's punches (124365 and above) were past the cap, so they are not in that sample. Record 1 from about twelve months ago is still stored, and the reported count is in the same range as the highest record number seen later in the run. This log has not wrapped away its oldest row. The run never filled the log, so it does not show what the reader drops when storage is full, or whether twelve months is a configured limit.

### P13 — live door events did not arrive

NOT OBSERVED. Every walk that left a punch was checked. The live-event count is zero in each case: P1 enabled (124365), P1 frozen (124367–124369), P1 enabled again (124370–124372), both P11 open walks, both P11 shut walks, and the first post-reboot walk (124378–124380). The punches exist in the log. The callback the gateway uses for live attendance did not fire. This repeats the 13 September visit, across a full afternoon of walks rather than one attempt. Attendance on this reader has to be read from the log.

### P6 — record numbers, windows, and the reboot

The harness marks P6 NOT OBSERVED because one of its four parts failed. The parts are not equal.

**During the run, record numbers rose with time.** OBSERVED. From 124365 at 07:31:54 through 124390 at 07:54:49, including a few punches that were not the test user (124366, 124381, 124384). No later punch had a lower record number.

**The time-window check failed in the harness.** NOT OBSERVED. A query for reader-clock 12:26–13:27 returned 30 rows, and all 30 had timestamps outside that window. A query for 2026-10-05 13:26 through 2026-10-06 13:26 returned 477 rows, 135 of them outside. Those stored timestamps are the UTC values from the clock section. The 30-row window is the same hour as this run: the test user's punches in it are 07:31–07:54 UTC, which is 13:01–13:24 India time, inside the window that was asked for. The harness compared the UTC stamp with the India-local bounds, so every row looked outside. This run shows the offset. It does not, by itself, show that the reader returned punches from the wrong hour.

**Reboot continuation is UNKNOWN, and the first trial's "NO" is not a reset.** The operator powered the reader off and on twice.

- Trial 1: the SDK disconnect callback arrived. The reconnect callback did not. Fresh logins timed out, then a new login succeeded at reader clock 13:38:36. The walk opened the door. The punches the probe attached to that walk are 124378, 124379, and 124380, timed 07:38–07:40 UTC (13:08–13:10 India time), which is before the reboot and below the record number already seen (124390). The walk search takes every not-yet-attributed punch for the test user in a ±14 hour window. Those three are earlier face hits that no walk prompt had claimed. They are not the post-reboot punch, so they do not show that record numbers went backwards.
- Trial 2: disconnect and reconnect callbacks both arrived, and the old login answered a user read. The operator reported the door open. The probe found no new punch for `990001`, so continuation was not shown.
- The "26 of 0" and "47 of 0" lines compare punch timestamps with the reader-local run start. Punch times are UTC and the run start is 13:00 local, so the denominator is zero. Those lines do not measure how many of the run's punches survived.

**A full log was not tested.** UNKNOWN, as required on a gym reader without `--spare-reader`.

P6 therefore does not show that `nRecNo` resets on reboot, and it does not show the full-log case. It does show record numbers increasing through an ordinary afternoon. The window verdict should be read together with P18.

### P18

NOT OBSERVED, as recorded in the clock section. Add 5 h 30 min to a stored punch to place it on this reader's clock.

## P19 and P20 — menu and reconnect

P19 is closed by the operator, not by the four skipped face tries. The probe did set `emAuthority` to Administrators and read it back, then set Customer and read that back. Nobody completed the face-to-menu walks. After the run, the operator checked the device: the add/edit menu is offered to everyone, and it asks for the reader credentials. Anyone who knows those credentials can add, edit, and run the other menu actions. A face marked Administrators is not what unlocks that menu.

Section 6 of the architecture doc treats SDK authority as "ADMIN permits device-management operations; USER does not." That does not match this reader. Device-side enrollment still happens, and the actor is whoever has the reader password. The server cannot tell which person typed it. Projecting `emAuthority=Administrators` does not grant or restrict that menu.

P20 is UNKNOWN. It is judged from the same two power cycles as P6.

| Trial | Disconnect callback | Reconnect callback | Old login answers `GetUser` | Door event on that login |
| --- | --- | --- | --- | --- |
| 1 | seen | not seen | no; a new login was made | not judged (new login) |
| 2 | seen | seen | yes | no stored punch, so not judged |

P13 already showed that walks before the reboot produced no live door event, so a missing event after the reboot says nothing about reconnect. Auto-reconnect happened once and failed once. The reader is on WiFi, and cutting its power also dropped the network the probe PC was using. Trial 1's failed reconnect is consistent with that. It is weak evidence about the SDK's own reconnect.

## P12

Closed without a trial. The operator confirmed this reader cannot be factory-reset, so there is no reset alarm or empty-state signature to capture. The architecture's "reader becomes empty, server rebuilds it" path still applies when the unit is replaced. It is not an in-place operation on this firmware. After an ordinary test-user delete, GET reported no record. That note is not an empty-reader signal.

## What this closes for the sync design

These are readings of this run, for the decisions in the architecture doc and the migration assessment. They are not a second verdict layered on top of the harness.

| Decision | Reading |
| --- | --- |
| Can freeze enforce "no entry" without deleting the person? | Yes on this firmware. Denied punches use error `0xA4`. |
| Can a partial user write patch one field? | No. A name insert zeroed both validity times. Send the full validity window on every user write. The face is separate. |
| Can `stuUpdateTime` order device edits? | No. It stayed zero across every mutation that could be read back. |
| Can alarms replace a user scan? | No. The SDK edits that sync cares about were silent. Screen noise (`EVENT_MOTIONDETECT` and others) is not an edit feed. |
| How does the screen pick `szUserID`? | Smallest free numeric id, in both samples. |
| May two users hold one face? | Yes. The SDK accepted it and both copies matched. |
| Who can add or edit users on the reader? | Anyone who can open the menu and enter the reader credentials. `emAuthority=Administrators` is not that gate. Do not project device ADMIN as menu permission. |
| How is a face replaced? | UPDATE, or remove then INSERT. A second INSERT returns `PHOTO_EXIST`. |
| How is "no such user" told from "no photo"? | Same SDK error `0x800004B5`. Use the fail code: `NO_RECORD` versus `UNKNOWN`. REMOVE of either, when already absent, returns ok. |
| Is a repeated full user list trustworthy when idle? | Yes, when the count matches the announced total. |
| Which name does the screen show? | `szName`. `szNameEx` holds the 127-character form. A `szName`-only write dropped `szNameEx`. |
| What clock are validity dates on? | The reader-local date. This reader matched India Standard Time. End of today opened; start of tomorrow stayed shut with error `0x14`. |
| What clock are punch timestamps on? | UTC, 5 h 30 min behind that reader clock. Convert before comparing, displaying, or bounding a query by the device clock. |
| Can attendance be tailed with "after `nRecNo`"? | No such bound exists. Use a time window. Record-number sort inside a window did work. |
| Do live access events carry the punch? | Not on this reader. Poll the log. |
| How much history is on the reader today? | At least back to 2025-10-12, record 1 still present, about 124,000 records reported. The drop policy when full is still unmeasured. |
| Does the 120 KB gateway photo cap match the reader? | This reader accepted 200 KB and stored a 17 KB re-encode. Byte-identical read-back held for the original 44 KB file and did not hold for the padded files. |

## Still open

These do not block member sync. They bound attendance on a spare reader. The gateway stays on Windows.

| Item | Why it is still open |
| --- | --- |
| P6 full log | Not run. Needs a spare reader. This gym reader is not the place to fill the log. |
| P6 record numbers across power loss | The two reboots did not produce a clean before/after punch. |
| P8 retention limit | Oldest row is a year old and still present. The full-log behavior is untested, and the download stopped at 50,000. |
| P10 safe parallelism and roster ceiling | One reader, one overlapped call, about 1,200 users. |
| P20 reconnect as a rule | One success and one failure, and the failure is mixed up with the WiFi drop. |
| Any second reader or second firmware | This serial only. |

## Enough to proceed

Yes, for member sync on this firmware. Freeze, validity, partial writes, faces, names, screen ids, duplicate faces, roster reads, and the absence of an edit alarm are all measured. P19 and P12 no longer wait on another visit.

Two design corrections follow from the operator's check, and they replace section 6's device-role row and the P12 reset gate for this reader:

- Do not project `emAuthority=Administrators` as permission to manage the reader. The menu asks for the reader password, and any person who has it can add and edit. Server-side admin and the reader password stay separate. Device edits are still observations for review. The server still cannot name which person made them.
- Do not wait for a factory-reset alarm. This unit cannot be reset in place. A replaced reader is an empty device: apply the server projection, including faces. Keep the existing guard that a suddenly empty roster is a bad read, not a mass delete.

Attendance can be designed from what is already known: poll the log, query by time window, treat punch timestamps as UTC and validity as India local time, and do not tail by record number. Claiming that record numbers survive a full disk or a power loss still needs a spare reader. That work is isolated from member sync.

## Cleanup

The probe removed `990001`, `1210`, and `1211` (face and user). `990002` and `990003` were already gone. The user list after that was the same 1,209 ids as at the start.
