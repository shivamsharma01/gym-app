# Product follow-ups

Open product decisions and remaining work after the original 0–9 roadmap.

## Remote face enroll (decision)

| Finding | Status |
| --- | --- |
| Live visit 2026-09-13 on serial `TW30000005250265` | Remote JPEG INSERT returned SDK success (`0x00000000`); door recognized the person |
| Product default today | **Guided on-device enroll remains the supported path** for member face capture |
| Remote INSERT | **Promising on this unit only** — do not mark product-complete until verified on ≥2 additional TrueFace units |
| Gateway `ENROLL_FACE` | Continues to report `GUIDED_PENDING` / not remote-success until multi-device validation lands |

See [TrueFaceWindowsPOC/docs/GYM-VISIT-2026-09-13.md](../TrueFaceWindowsPOC/docs/GYM-VISIT-2026-09-13.md).

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
