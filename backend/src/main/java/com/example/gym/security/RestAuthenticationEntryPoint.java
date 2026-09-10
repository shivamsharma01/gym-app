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
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Returns a 401 RFC 9457 problem detail for unauthenticated access to protected resources. */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper jsonMapper;

    public RestAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Authentication is required");
        pd.setType(URI.create("urn:gym:error:unauthenticated"));
        pd.setTitle(HttpStatus.UNAUTHORIZED.getReasonPhrase());
        pd.setProperty("code", "UNAUTHENTICATED");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("correlationId", correlationId(request));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(jsonMapper.writeValueAsString(pd));
    }

    private String correlationId(HttpServletRequest request) {
        Object cid = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return cid == null ? "n/a" : cid.toString();
    }
}
