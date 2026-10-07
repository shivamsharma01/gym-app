# Sync gates runbook (third POC)

`--gates` runs checks P1–P12 from section 18 of
[gym-device-sync-before-poc-architecture.md](../../../docs/architecture/gym-device-sync-before-poc-architecture.md),
plus P13–P21 for hardware behavior the current gateway code relies on but no earlier visit verified,
on one TrueFace reader. Each check gets one of three verdicts:

| Verdict      | Meaning                                                                                   |
| ------------ | ----------------------------------------------------------------------------------------- |
| OBSERVED     | The statement in the question was seen. For "how" or "what" questions, one consistent answer was seen. |
| NOT OBSERVED | The opposite was seen, consistently.                                                      |
| UNKNOWN      | There was not enough clear evidence.                                                      |

**Do not force an answer.** The verdict is UNKNOWN in any of these cases:

- a step was skipped;
- a read failed;
- only one trial was run;
- the operator and the stored punch disagree;
- the SDK reported success but the behavior itself was never seen in a read-back, a door walk, or a punch.

Copy UNKNOWN into the architecture document as UNKNOWN. Do not reinterpret the evidence to reach an answer.

## Safety rules

- **Never factory-reset a gym reader. Never fill its attendance log.** Those steps only run with `--spare-reader`, and P12 also requires you to type `RESET`. Without the flag, P12 and the full-log part of P6 stay UNKNOWN. That is the expected result.
- The probe writes only test users whose ids were free when the run started, named `SYNCPOC ...`. At the end it deletes them and compares the user list with the one it read at the start. Existing members are never written.
- The probe refuses to run while the Gym Gateway service is running. Stop it with `sc stop "Gym Gateway"`; otherwise the gateway would report the test users to the server.
- Fully quit IAS (Interactive Attendance), SmartPSS, and any other program that talks to this reader. Their writes and log reads would land in the middle of the probe's before and after readings.
- Do not commit the reader password, face photos, or the output folder. Pass the password with `--password` or the `TRUEFACE_PASSWORD` environment variable.
- If the run is interrupted, the report lists the test user ids that are still on the reader. Delete them from the reader screen.

## Before you go

1. Publish the probe for Windows x64. The `native\win-x64` folder must be next to the exe.
2. Pick a quiet time. Members walking in at the same moment do not break the run, because the probe only counts punches from the test user. They do make the alarm list longer.
3. Optional: bring `--photo face.jpg` (the face of the person who will do the door walks, at most 120 KB). Without it, the probe asks you to enrol your face on the reader screen for the test user.
4. Optional: bring `--photo2 other.jpg` (a different photo of the same person) for the face-replace step. Without it, the face-replace parts of P4 and P5 stay UNKNOWN.
5. Allow about 65 minutes, or about 80 minutes with the reboots.

## Run

```powershell
$env:TRUEFACE_PASSWORD = "<reader password>"
.\Gym.Gateway.SdkProbe.exe --reader 192.168.1.201 --gates --photo C:\walker.jpg --photo2 C:\walker2.jpg
```

If the Gym Gateway service is installed on that PC, run `sc stop "Gym Gateway"` first and `sc start "Gym Gateway"` afterwards. If it is not installed, skip both. The probe needs nothing else on the PC. If the reader user is not `admin`, add `--username <name>`.

Other options:

- `--test-user <id>`: first id to try for the test users. The default is 990001; the probe skips ids that are taken.
- `--no-walks`: run without anyone at the door. Every check that needs a walk stays UNKNOWN.
- `--step-delay <s>`: wait after each write before reading alarms. The default is 3 seconds.
- `--log-cap <n>`: the most attendance records P8 reads.

## What you will be asked

The prompts always say what to type. The last option, `k`, always means skip, and a skip is recorded as UNKNOWN.

| Prompt                     | What to do                                                                                                                                                            |
| -------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Walk to the reader         | Stand in front of the reader as the test user. Type `o` if the door opened, `s` if it stayed shut, `u` if you walked but are not sure, `k` if you did not walk. Answer what you saw, not what you expected. |
| P2 screen create (×2)      | On the reader screen, add a new person named exactly as shown (`SYNCPOC SCREEN 1`, then `2`). If the screen suggests an id, keep it. Then say whether the screen suggested the id or you typed it. |
| P5 screen edit (×2)        | On the reader screen, open the test user and change its name.                                                                                                        |
| P5 screen face (×2)        | On the reader screen, open the test user and enrol the face again (same person). A trial counts only if the stored photo changed.                                    |
| P17 screen                 | Open the test user on the reader screen and say which name is shown.                                                                                                 |
| P19 admin menu (×4)        | Try to open the reader's admin menu with your face: twice while the test user is an administrator, twice while it is a normal user. Close the menu without changing anything. |
| P6 reboot (×2)             | Type `r`, then power-cycle the reader. The probe first waits up to 6 minutes for the SDK to reconnect its existing login (P20), logs in again only if that fails, and then asks for one walk. If the reader cannot be rebooted, type `n` and unplug the reader's network cable for about a minute instead: P20 is still tested, but that trial counts as UNKNOWN for P6. |
| P6 full log / P12 reset    | Only shown with `--spare-reader`. Answer only if this really is a spare reader.                                                                                      |

## What each check does

| Check | Steps                                                                                                                                                             | Stays UNKNOWN when                                       |
| ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| P1    | Walk while enabled, set `nUserStatus=1` and read it back, walk twice while frozen, unfreeze, walk again.                                                         | Either enabled walk did not clearly open the door, or the two frozen walks were unclear or disagreed. |
| P2    | List the users, create a person on the screen, list again, and look at the new id. Do this twice. A rule (highest + 1, or smallest free) is stated only if both screen-suggested ids fit it. | An id was typed by hand, a trial was skipped, or both rules fit equally. |
| P3    | Write only the id and name over a full test user, then dump every field before and after. Do this twice.                                                         | A write or read failed, or the two trials differed.      |
| P4    | For name, validity, freeze, authority, face replace and delete: read the user and photo update times, make the change, read them again. Two trials each. Face replace is judged on the photo time, delete only records that GET misses the user, and the rest are judged on the user time. | A trial was unreadable, or the trials differed. Even an update time that moves every time is not counted as safe for ordering changes. |
| P5    | Drain the raw alarm codes and payloads around each SDK change, delete, screen create, screen edit and screen face enrolment. Two trials each.                                          | The trials raised different codes, or a trial was skipped. |
| P6    | Record numbers during this run, two time-window queries, the record number after each of two reboots, and (only with `--spare-reader`) a full log.               | Without `--spare-reader` the full-log part stays UNKNOWN, so P6 can be UNKNOWN or NOT OBSERVED but never OBSERVED. |
| P7    | Notes that the SDK find condition has no "after record number" bound, and checks whether ascending and descending record-number order are honored.             | Not applicable: the bound is missing from the SDK struct itself. |
| P8    | Reads the whole log (up to `--log-cap`) and reports the oldest and newest punch and the record count.                                                            | Always. The oldest punch present does not tell you the retention policy. |
| P9    | Puts the same face on test users A and B and reads both back. Two trials.                                                                                       | A read failed, the SDK error did not say why, or the trials differed. |
| P10   | Times the user list, a few `GetUser` and `GetFace` calls, and one pair of overlapping calls on the test user.                                                   | Always. One sample does not set a safe limit.           |
| P11   | Writes a validity that ends today, then one that starts tomorrow (by the reader's own date). Reads back the stored dates, compares the reader clock with the PC, and walks twice in each state. | The walks are unclear. Stored dates that differ from the sent ones are flagged as DIFFERENT. |
| P12   | Only with `--spare-reader` and `RESET`: you factory-reset the reader from its menu; the probe logs in again and records alarms, user count and record count.      | Without the flag (the normal case), or when the reader cannot be reached after the reset. |
| P13   | For every walk in the run except the post-reboot walks (those belong to P20), compares the live door events (`ALARM_ACCESS_CTL_EVENT`, with user, record number, error code and time) with the punches stored for that walk. | Fewer than two walks left a punch, or events arrived on some walks only. |
| P14   | Writes a face, reads it back twice and compares MD5s with what was sent; then records the answer to a face INSERT over an existing photo and a face UPDATE with no photo. Two trials. | A write or read failed, or the trials gave different answers. |
| P15   | Creates test user C without a face, then records the raw answers to: GET its photo, REMOVE its photo, REMOVE the user twice, GET the removed user. Two trials.   | Creating C failed, an answer had no fail code, or the trials differed. |
| P16   | Reads the full user list twice with no writes in between; compares the count with the announced total and the two id sets.                                       | A read failed. Staff editing users on the screen during the read would make it NOT OBSERVED falsely, so keep the screen idle. |
| P17   | Writes a 127-character `szNameEx` with a 31-character `szName`, reads both back; then writes `szName` only with `bUseNameEx=false`. Two trials, plus a screen check. | A write or read failed, or the trials differed. |
| P18   | Every walk reads punches over ±14 hours around the reader clock, so a punch stored in another time zone is still found. Checks that each punch time is within a minute of the reader clock at the walk. | Fewer than two walks left a punch, the ±14 h read hit its cap, or walks disagreed. |
| P19   | Sets `emAuthority=Administrators` on the test user, tries to open the admin menu by face twice, then sets `Customer` and tries twice again.                      | Any try was skipped or unclear, or the normal user also opened the menu. |
| P20   | During each P6 reboot, watches the existing login for the SDK disconnect and reconnect callbacks, checks that the old login answers a user read, and checks that the post-reboot walk's door event arrives on it. | A reboot was skipped or could not be confirmed (no callback at all), or no door event arrived on any walk before the reboots (then P13 already explains it). |
| P21   | Pads the walker's photo with JPEG comment blocks (the image itself is unchanged) to 100, 130 and 200 KB; for each size, writes it twice and records the answer and what is stored. The gateway currently refuses anything above 120 KB without asking the reader. | No photo, a read failed, or the trials differed. |

P19 gives the test user admin rights on the reader for a few minutes. The probe sets it back before moving on, and cleanup deletes the user. If the run is interrupted during P19, delete the test user from the reader screen straight away.

## Companion check: Linux login

The gateway may run on Linux, but no Linux machine has logged in to a physical reader yet. This is not part of `--gates` (the probe is Windows-only). If a Linux x64 laptop is on the gym network, run [TrueFaceLinuxPOC](../../../TrueFaceLinuxPOC/README.md) against the same reader twice. It only logs in, prints the device info and logs out; nothing on the reader changes. Record OBSERVED only if both logins succeed and print the same serial as `report.txt`. Without a Linux laptop, the result stays UNKNOWN.

## Output

The probe writes everything to `sync-gates-<time>\` next to the exe:

- `report.txt`: the full log. It starts with the reader serial, the reader clock and the PC clock, then has one section per check with every write result, read-back and raw alarm. At the end, the **Sync gate verdicts** section has one line per check with the verdict and a one-sentence reason.
- `gates.csv`: one row per check with id, verdict, question, reason and evidence.

Check the **Cleanup** section of the report. It should say `user list matches the start of the run`. If it lists added ids, they are people you created on the screen without the `SYNCPOC` name; delete them from the reader screen.
