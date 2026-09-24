# Device roster import — decisions for the gym

Guide for gym admins after TrueFace users already exist on the door devices and need to appear as Members in the app.

Face templates **stay on the devices**. This flow only creates Member / membership / mapping rows in the gym app. It does **not** copy biometrics to IAS or any other system.

---

## What we decided

| Topic | Decision |
|--------|----------|
| Same person on entrance + exit | One Member, mapped to **both** devices (matched by the device user id). |
| Membership plan on import | Auto-create a plan named **Unknown** (price 0). Admins replace it later with the real plan. |
| End date from device | Use the device validity end date when present. |
| Missing end date | Set end date to **today + 1 year** and mark **end date inferred**. Never treat missing data as lifetime access. |
| Start date | Device begin date if present; otherwise today. |
| Payment | Membership marked **paid**; **no** payment ledger row is created. |
| Frozen / disabled on device | Member is created **INACTIVE**. We do **not** push “enable” to the device. |
| Already active on device | Left usable on the device. Import does **not** disable them or re-upload faces. |
| Where the member came from | Shown as **Manual** (created in UI/API) or **Device** (imported). |
| Re-running import | Safe. Does not create duplicate members or mappings. |
| Face / template export | Not available. SUPER_ADMIN CSV export is roster fields only (names, codes, plans, dates, device ids). |

---

## What the gym must do (checklist)

### Before import

1. Ensure both entrance and exit devices are online and assigned to the gateway.
2. On a device detail page, run **Sync Now** (or reconcile) and wait until it finishes successfully.
3. Optionally open **Reconciliation conflicts**. Extra device users are expected until import; you do not need to remove them if you are about to import.

### Run import

4. On device detail (gym admin with device manage permission), click **Import device users**.
5. Read the summary: created / mapped / skipped / frozen→inactive / inferred end dates.
6. Press again if needed — it should mostly **skip** already-imported users.

### After import (manual work — required)

7. **Replace Unknown plans**  
   For each imported member, change membership to the correct plan (cancel/create or use your normal membership flows). The Unknown plan is only a placeholder so access bookkeeping can exist without guessing the real product.

8. **Fix inferred end dates**  
   On memberships marked “(end date inferred — review)”, set the real end date. Filter or scan members whose source is **Device** and plan is **Unknown**.

9. **Review frozen / inactive members**  
   Anyone who was frozen on the device is **INACTIVE** in the app. Confirm that is correct. Do not reactivate them unless the gym intends to restore access (and then unfreeze on the device as well via normal ops).

10. **Check names and member codes**  
    Names come from the device (first word = first name, rest = last name). Member code is usually the device user id. Correct typos in Member edit.

11. **Confirm both doors**  
    For people who used both entrance and exit, open the member and confirm mappings exist on both devices. Re-run import after another Sync Now if a sibling device was offline during the first import.

12. **Do not expect payment history**  
    Imported memberships are marked paid with **no** payment row. If you need accounting history, record payments manually only when that is a business requirement.

### Optional (platform)

13. SUPER_ADMIN can **Export members CSV** from the platform gyms list for IAS/hand-off. That file has **no** face data.

---

## What staff will see in the UI

- **Members** list: **Source** column — Manual vs Device.
- **Member detail**: badge for creation source; memberships may show “end date inferred — review”.
- **Device detail**: Sync Now, conflicts, **Import device users**, and an import summary after a run.

---

## What this does *not* do

- Does not move faces off the device.
- Does not invent the correct gym plan (always Unknown until staff fix it).
- Does not auto-activate people who were disabled/frozen on the device.
- Does not create payment receipts for imported memberships.
- Does not replace a careful review of dates for anyone marked inferred.

---

## Suggested order on go-live day

1. Sync Now on entrance and exit.  
2. Import device users (once or twice).  
3. Work the Unknown + inferred queues until plans and dates are correct.  
4. Spot-check a few members at the door (app access status + device still recognizes them).
