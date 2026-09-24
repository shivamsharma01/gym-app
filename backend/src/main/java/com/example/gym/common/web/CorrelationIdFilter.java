package com.example.gym.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns (or safely propagates) a correlation / request id per HTTP request.
 * Exposes {@code X-Correlation-Id} and {@code X-Request-Id} on the response and in SLF4J MDC.
 * Incoming values are accepted only when they match a conservative format (length + charset).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "correlationId";
    public static final String MDC_KEY = "correlationId";
    public static final String MDC_REQUEST_ID = "requestId";

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_.:\\-]{8,128}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(HEADER));
        if (correlationId == null) {
            correlationId = sanitize(request.getHeader(REQUEST_ID_HEADER));
        }
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        response.setHeader(REQUEST_ID_HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        MDC.put(MDC_REQUEST_ID, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(RequestLoggingFilter.MDC_TENANT);
            MDC.remove(RequestLoggingFilter.MDC_USER);
            MDC.remove(RequestLoggingFilter.MDC_METHOD);
            MDC.remove(RequestLoggingFilter.MDC_PATH);
            MDC.remove(RequestLoggingFilter.MDC_STATUS);
            MDC.remove(RequestLoggingFilter.MDC_DURATION);
        }
    }

    static String sanitize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        return SAFE_ID.matcher(trimmed).matches() ? trimmed : null;
    }
}
