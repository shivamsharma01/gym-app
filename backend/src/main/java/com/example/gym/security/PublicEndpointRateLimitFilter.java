package com.example.gym.security;

import com.example.gym.security.ratelimit.RateLimitProperties;
import com.example.gym.security.ratelimit.RateLimitResponses;
import com.example.gym.security.ratelimit.RateLimitStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * IP-based limits for unauthenticated / public auth surfaces (login, refresh, public enquiries).
 * Runs before JWT. Single-node in-memory store; edge nginx can add coarse IP protection.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PublicEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;

    private final RateLimitProperties properties;
    private final RateLimitStore store;
    private final JsonMapper jsonMapper;

    public PublicEndpointRateLimitFilter(RateLimitProperties properties,
                                         RateLimitStore store,
                                         JsonMapper jsonMapper) {
        this.properties = properties;
        this.store = store;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isEnabled() || !"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        Integer limit = null;
        String bucket = null;
        if ("/api/v1/auth/login".equals(path)) {
            limit = Math.max(1, properties.getLoginPerMinute());
            bucket = "login";
        } else if ("/api/v1/auth/refresh".equals(path)) {
            limit = Math.max(1, properties.getRefreshPerMinute());
            bucket = "refresh";
        } else if (path != null && path.startsWith("/api/v1/public/") && path.endsWith("/enquiries")) {
            limit = Math.max(1, properties.getEnquiryPerMinute());
            bucket = "enquiry";
        } else if ("/api/v1/public/enquiries".equals(path)) {
            limit = Math.max(1, properties.getEnquiryPerMinute());
            bucket = "enquiry";
        }

        if (limit == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = bucket + "|ip|" + RateLimitResponses.clientIp(request);
        if (!store.tryConsume(key, limit, WINDOW_MS)) {
            RateLimitResponses.writeTooManyRequests(request, response, jsonMapper, properties.getRetryAfterSeconds());
            return;
        }

        filterChain.doFilter(request, response);
    }
}
