# Product follow-ups

Open product decisions and remaining work after the original 0–9 roadmap.

## Remote face enroll (decision)

| Finding | Status |
| --- | --- |
| Live visit 2026-09-13 on serial `TW30000005250265` | Remote JPEG INSERT returned SDK success (`0x00000000`); door recognized the person |
| Product default today | **Two-way sync**: photo from React (Take/Choose photo) or enrolment on any device; the server stores it and pushes it to every device |
| Gateway `UPSERT_FACE` / `DELETE_FACE` | Real `OperateAccessFaceService` writes; image downloaded over REST with a sha256 check |
| Gateway `ENROLL_FACE` | Retired (returns failure); replaced by `UPSERT_FACE` |

See [TrueFaceWindowsPOC/docs/GYM-VISIT-2026-09-13.md](../TrueFaceWindowsPOC/docs/GYM-VISIT-2026-09-13.md).

## Two-way member and face sync

- Every member is on every device that has a gateway, with device user id = member code. Staff never type codes; the app generates them.
- **The gateway keeps its devices in sync by itself.** It holds a local copy of every member (name, frozen, validity, face, deletion) with the time each part last changed, saved on disk (`members/` next to the gateway config, photos in `members/faces`). When a device user is added, edited or deleted (device alarm + 60 s roster check, faces on a 30 min sweep), the gateway updates its copy and writes the change to its other devices straight away, even with no internet. Example: a person enrolled on the entrance device while the server is unreachable can leave through the exit device a minute later.
- A device that was switched off or unreachable is brought up to date on its next scan. Before writing to a device, the gateway re-reads it, so an edit on that device that hasn't been picked up yet is merged, not overwritten.
- **Reports to the server** (`DEVICE_USER_CHANGED`) go into a queue on disk and are sent in order when the server is reachable. Photos are uploaded from the gateway's own photo store. If the server is unreachable, the queue waits and is retried on every scan. A photo the server rejects as invalid is left out so the user's other changes still sync.
- **Commands from the server** carry the change time of what they write (`nameChangedAt`, `accessChangedAt`, `faceChangedAt`, `deletedAt`). The gateway applies them to all its devices unless its own copy is newer. In that case it skips the command and answers `skipped`; the server records this as **Sync change ignored** in the audit log. So after a long outage, the order in which queued messages arrive doesn't matter: both sides settle on the same result.
- **Only the parts a device changed are considered** (name, access, face, deletion). A change made on one side only is always applied. When both sides changed the same part, the **later change wins**. The losing change is never applied silently: it is written to the gateway log (`Latest change wins …`) and to the member's audit log as **Sync change ignored**, with both times.
- **Change times:** faces use the device's own time. TrueFace stores no edit time for names, access or deletions, so the gateway uses the time it noticed the change. For a device connected to the gateway that is within about a minute. For a device cut off from the gateway, it is the reconnect time, so such an edit usually wins over earlier changes. Keep Windows time sync on for the gateway PC.
- **What a device holds for access.** A device keeps one start date, one end date, and enabled or disabled per user. The server takes them from the member's memberships (cancelled ones are ignored):
  - The dates come from the membership running today; otherwise from the next one; otherwise from the last one, which has ended. Memberships that run back to back (or overlap) and are both paid are joined into one window, e.g. Jan 1–31 plus Feb 1–28 is sent as Jan 1–Feb 28.
  - Enabled means: the member is active, the membership is paid (partly paid counts), and it isn't frozen. Otherwise the member is sent with the same dates but disabled.
  - The device refuses entry outside its dates by itself. So with Jan 1–31 and Feb 5–Mar 4, nobody gets in on Feb 1–4 and no command is needed for that.
  - A paid membership that hasn't started yet is sent enabled, because the device waits for the start date. For devices that don't check dates, set `app.gateway.devices-enforce-validity-dates=false`: the member is then sent disabled and enabled on the start day.
  - The end date is valid until 23:59:59 on the device.
- **Changeover between memberships.** An access check runs every hour and at server start. When the membership on the devices has ended:
  - If there's a next membership, its dates are sent, enabled if it's paid.
  - If there isn't, the member is disabled on the devices and keeps the old dates. The member stays active in the app and the membership shows as Expired.

  Only members whose window actually changes get commands. Recording a payment updates the devices immediately if it affects the membership currently on them. Adding a future membership doesn't touch the devices until its turn. "Today" is the server's local date, so the server runs in the gym's time zone (`GYM_TIMEZONE`, default Asia/Kolkata). If the gateway is offline at changeover, the switch reaches the devices when it reconnects; until then they refuse entry by the old dates.
- **Frozen / validity edited on a device** are applied as the device holds them, when only the device changed (or its change is the later one):
  - Disabling freezes the running membership. Enabling unfreezes it (and reactivates an inactive member).
  - Date edits change the membership(s) in the window: a new start date goes to the first, a new end date to the last. This also works on a frozen membership. If the new dates overlap another membership, they're still applied, and a **Membership overlap** conflict is opened for staff; the other membership is not changed.
  - Payment is the one rule a device can't know about. Enabling a member whose membership is unpaid (or who has no running membership) is refused: the device is disabled again and the member's audit log says why.
- Deleting a user on a device deletes the member from the system: the member is deactivated (payments and history are kept) and removed from every device. If the member changed on the server after the deletion, the deletion is ignored (and logged) and the member is put back on every device. A stale edit reported for a deleted member is ignored (and logged) and the user is removed again; an edit made after the deletion brings the member back everywhere. Reactivating the member in the app also puts it back on all devices.
- Deleting a face on a device removes the photo from the server and the other devices, unless the photo was changed later elsewhere.
- **Code clash:** if a device user is new to the gateway but uses the code of an existing member with a different name (enrolled while offline), it is not merged into that member. It becomes a separate member with a new code (re-written to every device under that code), the existing member is written back under its own code, and a **Device code clash** conflict is opened for staff. Door events recorded in between are attributed to the existing member.
- If many users disappear from a device in one scan (more than 5 and more than 20 % of its roster, e.g. a factory reset), or the device returns an empty list, the gateway treats it as a read problem, not as deletions, and logs a warning. As a result, deleting the only user on a device is not detected.
- Echoes are suppressed: the gateway records the version of each part it wrote to each device, and the server ignores a face identical to the stored one.
- Users that existed on a device before the gateway first saw it are imported by reconcile, and their face is requested with `REPORT_DEVICE_USER`.
- There is no "unmap" action: a member is either on every device or deleted/deactivated.

### Manual two-device check (before go-live)

1. Two TrueFace units on one gateway, both ONLINE on the Devices page.
2. React: create a member with **Take photo** → the member page's Device sync section shows both devices reach “Photo on device”; the face opens the door on both.
3. Device A: enrol a new user with a face → within ~1 minute device B recognises the face, and a new member (“Created from device”) appears in React with that photo.
4. Device B: replace that user’s face → device A gets the new face and the React photo updates (source: device B).
5. React: rename the member → both devices show the new name. Rename on a device → the other device and React show it.
6. Device A: change a user’s validity dates, then disable it → device B follows both; React shows the new end date, then the membership as frozen. Enable it again on device A → unfrozen everywhere.
7. Device A: delete a user’s face → device B no longer recognises the face and the React photo disappears.
8. Device A: delete the user → the user disappears from device B and the member becomes inactive in React. Reactivate in React → it is back on both devices.
9. **Offline:** unplug the server connection. Enrol a new user on device A → device B recognises them within a minute. Rename another user on device B → device A shows it. Reconnect → React shows both changes within a minute.
10. **Conflict:** offline, rename a user on device A; then rename the same member in React; reconnect → the later rename is on both devices and in React, and the member's audit log shows the other as **Sync change ignored**.
11. **Changeover:** give a member a paid membership ending yesterday and an unpaid one starting in 3 days. Within an hour (or after a server restart) both devices show the new dates with the user disabled. Record the payment → the user is enabled at once, and the door still refuses them until the start date. On the last day of a membership the door still opens.
12. Gateway logs show no repeated `changed locally` lines for users the gateway itself wrote (no echo loop).

## ACCESS events

Live door allow can succeed without the SDK delivering `ALARM_ACCESS_CTL_EVENT` to our listener. The Windows POC now:

1. Waits for live ACCESS callbacks
2. Polls attendance records as a fallback
3. Accepts operator `YES` confirmation for grant/deny when neither yields evidence

Gateway cutover should use the same defense in depth when wiring attendance to the backend.

## Deferred product (not blocking go-live of staff UI)

- Real SMS/email notifications (mock today)
- Real payment provider
- Forgot-password email flow
- SUPER_ADMIN “act as gym” UX
- Custom domains / CDN / email invites

## Ops

See [deploy/README.md](../deploy/README.md) for TLS, bootstrap, rate limits, and Windows gateway cutover.

## Device roster import (IAS / existing TrueFace users)

When a gym already has users enrolled on TrueFace devices and needs them as app Members: see [DEVICE-ROSTER-IMPORT.md](DEVICE-ROSTER-IMPORT.md) for product decisions and the admin checklist (Unknown plan, inferred end dates, frozen → inactive, no face export).
