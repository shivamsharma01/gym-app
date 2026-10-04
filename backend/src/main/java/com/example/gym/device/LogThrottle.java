package com.example.gym.device;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Lets a repeating diagnostic through once per key and window, so a stuck state is reported without flooding. */
final class LogThrottle {

    private final Duration window;
    private final Map<String, Instant> last = new ConcurrentHashMap<>();

    LogThrottle(Duration window) {
        this.window = window;
    }

    boolean allow(String key) {
        Instant now = Instant.now();
        Instant previous = last.get(key);
        if (previous != null && previous.plus(window).isAfter(now)) {
            return false;
        }
        last.put(key, now);
        return true;
    }
}
