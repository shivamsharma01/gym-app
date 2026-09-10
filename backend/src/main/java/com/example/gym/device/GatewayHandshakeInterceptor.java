package com.example.gym.device;

import com.example.gym.device.GatewayAuthService.Kind;
import com.example.gym.device.GatewayAuthService.Outcome;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Authenticates the gateway WSS handshake with a per-gateway token (preferred) or the optional
 * deployment shared token. A matching per-gateway token binds the session to that gateway so it
 * cannot impersonate another.
 */
@Component
public class GatewayHandshakeInterceptor implements HandshakeInterceptor {

    static final String GATEWAY_ID_ATTR = "gatewayId";

    private static final Logger log = LoggerFactory.getLogger(GatewayHandshakeInterceptor.class);

    private final GatewayAuthService authService;

    public GatewayHandshakeInterceptor(GatewayAuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Outcome outcome = authService.authenticate(presentedToken(request)).orElse(null);
        if (outcome == null) {
            log.warn("Rejecting gateway handshake: invalid or missing token");
            return false;
        }
        if (outcome.kind() == Kind.GATEWAY) {
            attributes.put(GATEWAY_ID_ATTR, outcome.gateway().getPublicId());
        }
        return true;
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
            return servlet.getServletRequest().getParameter("token");
        }
        return null;
    }
}
