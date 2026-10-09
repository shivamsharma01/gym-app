# Reader timing notes

Measurements from the October 2026 local-network logs and the 7 October 2026 probe, for a gym of about 1,200 members and two TrueFace readers. They are not an end-to-end timing of desired-state apply. Member state is a per-reader desired revision, not a paced batch of member commands.

## What was measured

- Listing one reader's users took about 3 seconds, and about 3–5 seconds for the ~1,200-user roster in the probe.
- Photo reads were about 0.1 s per photo on 192.168.31.92 and 0.25 s on 192.168.31.91, one reader at a time. That is about 2–5 minutes per reader for 1,200 photos. A slow network (about 4 s per photo) stretches that into hours.
- A full user write that also moves a photo is on the order of 1–1.5 s for one person on one reader.
- The safety-scan interval is not fixed. Do not take 15 seconds from these numbers. A larger roster has not been measured.

## What that means for desired state

- Each reader is applied by its own worker. One reader's photo pass does not block the other reader's worker, but a single reader still spends the photo time above.
- Seeding a trusted empty reader writes each server member through the desired-state path, with read-back before ack. Budget the per-person write time. Do not copy the roster from the other reader to avoid that cost.
- A reconnect observes the reader before it applies. An unchanged reader should not repeat every face write. The journal is what makes a restart skip a revision that already verified.
- A short or failed list must not be treated as a fast path that deletes people.

## Not a scheduling rule

Do not schedule a full roster comparison for every gym at the same wall-clock minute. When many gyms exist, stagger safety scans. That is an operations concern, not a second sync design.
