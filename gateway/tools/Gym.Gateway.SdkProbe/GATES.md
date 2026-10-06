# Sync gates runbook (third POC)

`--gates` runs checks P1–P12 from section 18 of
[gym-device-sync-before-poc-architecture.md](../../../docs/architecture/gym-device-sync-before-poc-architecture.md)
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
5. Allow about 45 minutes, or about 60 minutes with the reboots.

## Run

```powershell
sc stop "Gym Gateway"
$env:TRUEFACE_PASSWORD = "<reader password>"
.\Gym.Gateway.SdkProbe.exe --reader 192.168.1.201 --gates --photo C:\walker.jpg --photo2 C:\walker2.jpg
sc start "Gym Gateway"
```

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
| P6 reboot (×2)             | Power-cycle the reader, then type `r`. The probe waits up to 6 minutes for the reader to come back and then asks for one walk. |
| P6 full log / P12 reset    | Only shown with `--spare-reader`. Answer only if this really is a spare reader.                                                                                      |

## What each check does

| Check | Steps                                                                                                                                                             | Stays UNKNOWN when                                       |
| ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| P1    | Walk while enabled, set `nUserStatus=1` and read it back, walk twice while frozen, unfreeze, walk again.                                                         | Either enabled walk did not clearly open the door, or the two frozen walks were unclear or disagreed. |
| P2    | List the users, create a person on the screen, list again, and look at the new id. Do this twice. A rule (highest + 1, or smallest free) is stated only if both screen-suggested ids fit it. | An id was typed by hand, a trial was skipped, or both rules fit equally. |
| P3    | Write only the id and name over a full test user, then dump every field before and after. Do this twice.                                                         | A write or read failed, or the two trials differed.      |
| P4    | For name, validity, freeze, authority, face replace and delete: read the user and photo update times, make the change, read them again. Two trials each. Face replace is judged on the photo time, delete only records that GET misses the user, and the rest are judged on the user time. | A trial was unreadable, or the trials differed. Even an update time that moves every time is not counted as safe for ordering changes. |
| P5    | Drain the raw alarm codes and payloads around each SDK change, delete, screen create and screen edit. Two trials each.                                           | The trials raised different codes, or a trial was skipped. |
| P6    | Record numbers during this run, two time-window queries, the record number after each of two reboots, and (only with `--spare-reader`) a full log.               | Without `--spare-reader` the full-log part stays UNKNOWN, so P6 can be UNKNOWN or NOT OBSERVED but never OBSERVED. |
| P7    | Notes that the SDK find condition has no "after record number" bound, and checks whether ascending and descending record-number order are honored.             | Not applicable: the bound is missing from the SDK struct itself. |
| P8    | Reads the whole log (up to `--log-cap`) and reports the oldest and newest punch and the record count.                                                            | Always. The oldest punch present does not tell you the retention policy. |
| P9    | Puts the same face on test users A and B and reads both back. Two trials.                                                                                       | A read failed, the SDK error did not say why, or the trials differed. |
| P10   | Times the user list, a few `GetUser` and `GetFace` calls, and one pair of overlapping calls on the test user.                                                   | Always. One sample does not set a safe limit.           |
| P11   | Writes a validity that ends today, then one that starts tomorrow (by the reader's own date). Reads back the stored dates, compares the reader clock with the PC, and walks twice in each state. | The walks are unclear. Stored dates that differ from the sent ones are flagged as DIFFERENT. |
| P12   | Only with `--spare-reader` and `RESET`: you factory-reset the reader from its menu; the probe logs in again and records alarms, user count and record count.      | Without the flag (the normal case), or when the reader cannot be reached after the reset. |

## Output

The probe writes everything to `sync-gates-<time>\` next to the exe:

- `report.txt`: the full log. It starts with the reader serial, the reader clock and the PC clock, then has one section per check with every write result, read-back and raw alarm. At the end, the **Sync gate verdicts** section has one line per check with the verdict and a one-sentence reason.
- `gates.csv`: one row per check with id, verdict, question, reason and evidence.

Check the **Cleanup** section of the report. It should say `user list matches the start of the run`. If it lists added ids, they are people you created on the screen without the `SYNCPOC` name; delete them from the reader screen.
