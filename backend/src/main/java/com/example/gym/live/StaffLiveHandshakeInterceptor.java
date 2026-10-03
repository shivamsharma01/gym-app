package com.example.gym.live;

import com.example.gym.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class StaffLiveHandshakeInterceptor implements HandshakeInterceptor {

    /** Echoed subprotocol. The JWT is a sibling value; browsers cannot set Authorization. */
    static final String BEARER_PROTOCOL = "bearer";

    private static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";

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

    /**
     * Prefer {@code Authorization: Bearer}. Browsers cannot set that header on a WebSocket,
     * so the SPA sends the same JWT as a {@code Sec-WebSocket-Protocol} value next to
     * {@code bearer}. Query parameters are ignored so the token never lands in the URL.
     */
    static String presentedToken(ServerHttpRequest request) {
        String header = bearerToken(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (header != null) {
            return header;
        }
        return protocolToken(request.getHeaders().get(SEC_WEBSOCKET_PROTOCOL));
    }

    static String bearerToken(String authorization) {
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authorization.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        return null;
    }

    static String protocolToken(List<String> protocols) {
        if (protocols == null) {
            return null;
        }
        String token = null;
        for (String raw : protocols) {
            if (raw == null) {
                continue;
            }
            for (String part : raw.split(",")) {
                String value = part.trim();
                if (value.isEmpty() || value.equalsIgnoreCase(BEARER_PROTOCOL)) {
                    continue;
                }
                if (token != null) {
                    return null;
                }
                token = value;
            }
        }
        return token;
    }
}
