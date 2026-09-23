package com.example.gym.security.ratelimit;

import com.example.gym.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

public final class RateLimitResponses {

    private RateLimitResponses() {
    }

    public static void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response,
                                     JsonMapper jsonMapper, int retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfterSeconds)));

        ProblemDetail pd = ProblemDetail.forStatus(429);
        pd.setTitle("Too Many Requests");
        pd.setDetail("Too many requests — try again shortly");
        pd.setType(URI.create("urn:gym:error:rate_limited"));
        pd.setProperty("code", "RATE_LIMITED");
        pd.setProperty("timestamp", Instant.now().toString());
        Object correlationId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        if (correlationId != null) {
            pd.setProperty("correlationId", correlationId.toString());
            pd.setProperty("requestId", correlationId.toString());
        }
        response.getWriter().write(jsonMapper.writeValueAsString(pd));
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
