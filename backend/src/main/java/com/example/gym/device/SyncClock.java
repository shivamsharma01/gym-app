package com.example.gym.device;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * The change time stamped on member fields for "latest change wins" with devices. Normally now;
 * while a device change is being applied it is that change's time, so the commands fanned out to
 * gateways carry the same time the gateway already has (and are recognised as no-ops there).
 */
public final class SyncClock {

    private static final ThreadLocal<Instant> OVERRIDE = new ThreadLocal<>();

    private SyncClock() {
    }

    public static Instant now() {
        Instant override = OVERRIDE.get();
        return override != null ? override : Instant.now();
    }

    public static <T> T at(Instant changedAt, Supplier<T> work) {
        Instant previous = OVERRIDE.get();
        OVERRIDE.set(changedAt);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        }
    }
}
