package com.example.gym.security;

import com.example.gym.security.ratelimit.RateLimitProperties;
import com.example.gym.security.ratelimit.RateLimitResponses;
import com.example.gym.security.ratelimit.RateLimitStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * User/tenant-aware limits for authenticated API traffic (after JWT).
 * Skips actuator and unauthenticated requests (those use {@link PublicEndpointRateLimitFilter}).
 */
public class AuthenticatedRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;

    private final RateLimitProperties properties;
    private final RateLimitStore store;
    private final JsonMapper jsonMapper;

    public AuthenticatedRateLimitFilter(RateLimitProperties properties,
                                        RateLimitStore store,
                                        JsonMapper jsonMapper) {
        this.properties = properties;
        this.store = store;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (path != null && (path.startsWith("/actuator") || path.startsWith("/gateway")
                || path.startsWith("/internal/gateway") || path.equals("/live"))) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        String userKey = "u:" + (principal.getUserId() != null ? principal.getUserId() : principal.getUsername());
        String tenantKey = principal.getTenantId() != null ? "t:" + principal.getTenantId() : "t:platform";

        if (path != null && path.startsWith("/api/v1/reports")) {
            String key = "reports|" + tenantKey + "|" + userKey;
            if (!store.tryConsume(key, Math.max(1, properties.getReportsPerMinute()), WINDOW_MS)) {
                RateLimitResponses.writeTooManyRequests(request, response, jsonMapper, properties.getRetryAfterSeconds());
                return;
            }
        }

        if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/v1/auth/me/password".equals(path)) {
            String key = "pwd|" + userKey;
            if (!store.tryConsume(key, Math.max(1, properties.getPasswordChangePerMinute()), WINDOW_MS)) {
                RateLimitResponses.writeTooManyRequests(request, response, jsonMapper, properties.getRetryAfterSeconds());
                return;
            }
        }

        if (path != null && path.startsWith("/api/")) {
            String key = "api|" + tenantKey + "|" + userKey;
            if (!store.tryConsume(key, Math.max(1, properties.getAuthenticatedPerMinute()), WINDOW_MS)) {
                RateLimitResponses.writeTooManyRequests(request, response, jsonMapper, properties.getRetryAfterSeconds());
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
