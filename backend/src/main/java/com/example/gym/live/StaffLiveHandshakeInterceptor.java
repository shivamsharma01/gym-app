package com.example.gym.live;

import com.example.gym.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class StaffLiveHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StaffLiveHandshakeInterceptor.class);

    private final JwtService jwtService;

    public StaffLiveHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = presentedToken(request);
        if (token == null) {
            log.warn("Rejecting staff live handshake: missing token");
            return false;
        }
        try {
            Claims claims = jwtService.parse(token);
            @SuppressWarnings("unchecked")
            List<String> authorities = claims.get("authorities", List.class);
            if (authorities == null || (!authorities.contains("ATTENDANCE_VIEW")
                    && !authorities.contains("DEVICE_VIEW")
                    && !authorities.contains("SECURITY_ALERT_VIEW"))) {
                log.warn("Rejecting staff live handshake: missing live-view permission");
                return false;
            }
            Number tid = claims.get("tid", Number.class);
            attributes.put(StaffLiveHub.TENANT_ATTR, tid == null ? null : tid.longValue());
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Rejecting staff live handshake: invalid token");
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private String presentedToken(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        if (request instanceof ServletServerHttpRequest servlet) {
            String query = servlet.getServletRequest().getParameter("access_token");
            if (query != null && !query.isBlank()) {
                return query;
            }
            return servlet.getServletRequest().getParameter("token");
        }
        return null;
    }
}
