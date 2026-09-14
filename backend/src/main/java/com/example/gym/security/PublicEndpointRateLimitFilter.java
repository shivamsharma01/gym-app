package com.example.gym.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Simple in-memory sliding-window limits for public auth login and public enquiries.
 * Suitable for single-node deploy; put a real edge limiter in front for multi-node production.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PublicEndpointRateLimitFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final int loginPerMinute;
    private final int enquiryPerMinute;
    private final ObjectMapper objectMapper;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public PublicEndpointRateLimitFilter(
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.login-per-minute:20}") int loginPerMinute,
            @Value("${app.rate-limit.enquiry-per-minute:10}") int enquiryPerMinute,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.loginPerMinute = Math.max(1, loginPerMinute);
        this.enquiryPerMinute = Math.max(1, enquiryPerMinute);
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!enabled || !"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        Integer limit = null;
        if ("/api/v1/auth/login".equals(path)) {
            limit = loginPerMinute;
        } else if (path != null && path.startsWith("/api/v1/public/") && path.endsWith("/enquiries")) {
            limit = enquiryPerMinute;
        } else if ("/api/v1/public/enquiries".equals(path)) {
            limit = enquiryPerMinute;
        }

        if (limit == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = path + "|" + clientKey(request);
        if (!allow(key, limit)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "error", "RATE_LIMITED",
                    "message", "Too many requests — try again shortly",
                    "timestamp", Instant.now().toString()));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean allow(String key, int limit) {
        long now = System.currentTimeMillis();
        long cutoff = now - 60_000L;
        Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < cutoff) {
                q.removeFirst();
            }
            if (q.size() >= limit) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
