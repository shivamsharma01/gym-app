package com.example.gym.common.web;

import com.example.gym.security.AppUserPrincipal;
import com.example.gym.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * After authentication, enriches MDC with tenant/user and emits a single structured access log line.
 * Registered in the security filter chain after JWT (see {@code SecurityConfig}).
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String MDC_TENANT = "tenantId";
    public static final String MDC_USER = "userId";
    public static final String MDC_METHOD = "httpMethod";
    public static final String MDC_PATH = "httpPath";
    public static final String MDC_STATUS = "httpStatus";
    public static final String MDC_DURATION = "durationMs";

    private static final Logger ACCESS = LoggerFactory.getLogger("gym.access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.nanoTime();
        MDC.put(MDC_METHOD, request.getMethod());
        MDC.put(MDC_PATH, request.getRequestURI());
        enrichPrincipal();
        try {
            filterChain.doFilter(request, response);
        } finally {
            enrichPrincipal();
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            MDC.put(MDC_STATUS, String.valueOf(response.getStatus()));
            MDC.put(MDC_DURATION, String.valueOf(durationMs));
            if (!isQuietPath(request.getRequestURI())) {
                ACCESS.info("method={} path={} status={} durationMs={} tenantId={} userId={}",
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus(),
                        durationMs,
                        MDC.get(MDC_TENANT),
                        MDC.get(MDC_USER));
            }
        }
    }

    private static void enrichPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal principal) {
            if (principal.getUserId() != null) {
                MDC.put(MDC_USER, String.valueOf(principal.getUserId()));
            }
            Long tenantId = principal.getTenantId() != null ? principal.getTenantId() : TenantContext.get();
            if (tenantId != null) {
                MDC.put(MDC_TENANT, String.valueOf(tenantId));
            } else {
                MDC.put(MDC_TENANT, "platform");
            }
        } else {
            Long tenantId = TenantContext.get();
            if (tenantId != null) {
                MDC.put(MDC_TENANT, String.valueOf(tenantId));
            }
        }
    }

    private static boolean isQuietPath(String path) {
        return path != null && (path.startsWith("/actuator/health") || path.equals("/error"));
    }
}
