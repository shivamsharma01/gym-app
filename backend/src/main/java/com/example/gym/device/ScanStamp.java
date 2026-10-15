package com.example.gym.device;

import tools.jackson.databind.JsonNode;

/**
 * Where an observation or a read-back sits in one reader's scan order: the gateway reader journal
 * ({@code epoch}) and the number of the scan within it ({@code seq}). The gateway numbers scans one
 * after another and stores the number before a scan is uploaded, and it takes scans and writes for
 * one reader one at a time, so within one journal a higher number is a later look at the reader.
 * Numbers from two journals are not compared: a journal the server has accepted since is newer, and
 * one it has retired is refused. Clocks are not used.
 */
public record ScanStamp(String epoch, long seq) {

    public static final int MAX_EPOCH = 64;

    /** The stamp on a payload, or null when it has none or it cannot be read. */
    public static ScanStamp of(JsonNode payload) {
        if (payload == null) {
            return null;
        }
        JsonNode epoch = payload.get("scanEpoch");
        JsonNode seq = payload.get("scanSeq");
        return of(epoch == null || epoch.isNull() ? null : epoch.asString(),
                seq == null || !seq.isIntegralNumber() ? null : seq.asLong());
    }

    public static ScanStamp of(String epoch, Long seq) {
        if (epoch == null || epoch.isBlank() || epoch.length() > MAX_EPOCH || seq == null || seq < 0) {
            return null;
        }
        return new ScanStamp(epoch.trim(), seq);
    }

    /**
     * True when this look at the reader came no later than the one recorded as
     * ({@code epoch}, {@code seq}) in the same journal. A record from another journal, or none, is
     * not later than this.
     */
    public boolean notAfter(String epoch, Long seq) {
        return seq != null && this.epoch.equals(epoch) && this.seq <= seq;
    }

    /** True when this look came strictly before the one recorded, in the same journal. */
    public boolean before(String epoch, Long seq) {
        return seq != null && this.epoch.equals(epoch) && this.seq < seq;
    }
}
