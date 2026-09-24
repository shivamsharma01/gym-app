package com.example.gym.security.ratelimit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Single-node sliding-window limiter. Suitable for one Spring Boot instance on a VPS.
 */
@Component
public class InMemoryRateLimitStore implements RateLimitStore {

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryConsume(String key, int limitPerWindow, long windowMillis) {
        long now = System.currentTimeMillis();
        long cutoff = now - windowMillis;
        Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < cutoff) {
                q.removeFirst();
            }
            if (q.size() >= limitPerWindow) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }
}
