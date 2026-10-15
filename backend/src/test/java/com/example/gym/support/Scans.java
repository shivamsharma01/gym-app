package com.example.gym.support;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Scan stamps as the gateway puts them on an observation: its reader journal and the number of the
 * scan the observation came from. Helpers number scans in the order they send them.
 */
public final class Scans {

    private static final AtomicLong LAST = new AtomicLong();

    private Scans() {
    }

    /** The payload as the gateway's next scan. */
    public static String stamp(String payload) {
        return stamp(payload, AbstractReaderIT.JOURNAL, LAST.incrementAndGet());
    }

    public static String stamp(String payload, String epoch, long seq) {
        LAST.accumulateAndGet(seq, Math::max);
        int open = payload.indexOf('{');
        return payload.substring(0, open + 1) + "\"scanEpoch\":\"" + epoch + "\",\"scanSeq\":" + seq
                + (payload.substring(open + 1).trim().startsWith("}") ? "" : ",") + payload.substring(open + 1);
    }

    /** The scan the gateway took last, which a read-back it acknowledges now follows. */
    public static long current() {
        return LAST.get();
    }

    /** A scan number above every one sent so far. */
    public static long next() {
        return LAST.incrementAndGet();
    }
}
