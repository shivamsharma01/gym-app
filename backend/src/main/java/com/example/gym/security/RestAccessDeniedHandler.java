package com.example.gym.security;

import com.example.gym.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Returns a 403 RFC 9457 problem detail when an authenticated caller lacks permission. */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public RestAccessDeniedHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You do not have permission to perform this action");
        pd.setType(URI.create("urn:gym:error:access_denied"));
        pd.setTitle(HttpStatus.FORBIDDEN.getReasonPhrase());
        pd.setProperty("code", "ACCESS_DENIED");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("correlationId", correlationId(request));

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(jsonMapper.writeValueAsString(pd));
    }

    private String correlationId(HttpServletRequest request) {
        Object cid = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return cid == null ? "n/a" : cid.toString();
    }
}
