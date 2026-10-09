---
applyTo: "frontend/**"
---

UI must expose unresolved conflicts rather than silently resolving them.

Never hide disagreement between server/device states.

Whole-record resolution is the current supported resolution model.

## Stack

React 19.3, TypeScript, Vite 8, React Router 7, TanStack Query for server
state. Tailwind v4 with shadcn/Radix, Recharts, Lucide, date-fns, Motion
(respect reduced motion). Never store JWTs in `localStorage`; use an
httpOnly cookie or an in-memory token with rotation.

The UI changes device state only through server APIs. It never talks to
readers or the gateway directly.

## Review queue

The review queue is part of the product, not a hidden error drawer. Every
item answers: what the server wanted, what each reader has, what was last
reconciled, what the system suggests, and what will happen after the
admin chooses.

- Side by side: server, each device, and baseline, covering name,
  validity, accessAllowed / effective access, role, face thumbnail or
  hash, deviceUserId, and presence.
- Evidence: source device, observed time, last reconciled revision,
  mapping, alarm or scan trigger, whether other readers agree.
- Actions: accept server, accept device snapshot, link to existing member,
  create new member, restore to reader, remove from all readers, reject
  provisional enrollment.
- Suggested matches are ranked by evidence (name, face, mapping, other
  fields). Never auto-merge identity.
- Show who decided and what changed (actor, prior state, chosen state,
  resulting revisions).
- Show the age of the oldest pending review item.

## Member access wording

- Keep "disable" (frozen on readers, record kept) and "remove from reader"
  (record deleted from that reader, member kept) as separate actions.
- A reader-side deletion never shows the member as deleted; show it as a
  device difference.
- Do not tell users that disable blocks the door until POC P1 confirms it.
- Face images are sensitive: show thumbnails only to authorized admins.
