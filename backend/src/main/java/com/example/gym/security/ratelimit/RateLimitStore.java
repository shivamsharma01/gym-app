package com.example.gym.security.ratelimit;

/**
 * Pluggable rate-limit store. Current deploy uses in-memory (single node).
 * A Redis-backed implementation can replace this without changing filters.
 */
public interface RateLimitStore {

    /**
     * @return {@code true} if the request is allowed; {@code false} if the limit is exceeded
     */
    boolean tryConsume(String key, int limitPerWindow, long windowMillis);
}
