package com.example.gym.device;

import com.example.gym.device.domain.Gateway;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Authenticates the gateway WebSocket handshake with the per-gateway operational credential.
 * The credential is read only from the {@code Authorization} header. A credential in the query
 * string is rejected. The session is bound to the gateway and to the hash of the credential that
 * authenticated it, so a later expiry or rotation can drop that socket. A gateway id in the query
 * string or a later message is never used.
 */
@Component
public class GatewayHandshakeInterceptor implements HandshakeInterceptor {

    static final String GATEWAY_ID_ATTR = "gatewayId";
    static final String CREDENTIAL_HASH_ATTR = "gatewayCredentialHash";

    private static final Logger log = LoggerFactory.getLogger(GatewayHandshakeInterceptor.class);

    private final GatewayAuthService authService;

    public GatewayHandshakeInterceptor(GatewayAuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (queryCredentialPresent(request)) {
            log.warn("Rejecting gateway handshake: operational credential must not be sent in the URL");
            return false;
        }
        String token = authorizationToken(request);
        Optional<Gateway> gateway = authService.authenticate(token);
        if (gateway.isEmpty()) {
            log.warn("Rejecting gateway handshake: invalid, missing, or expired token");
            return false;
        }
        String credentialHash = authService.hash(token);
        if (!authService.sessionAllows(gateway.get(), credentialHash)) {
            log.warn("Rejecting gateway handshake: credential is no longer valid for a websocket");
            return false;
        }
        attributes.put(GATEWAY_ID_ATTR, gateway.get().getPublicId());
        attributes.put(CREDENTIAL_HASH_ATTR, credentialHash);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    /** True when the URL carries a token parameter, including an empty one. */
    private boolean queryCredentialPresent(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servlet) {
            return servlet.getServletRequest().getParameter("token") != null;
        }
        return false;
    }

    private String authorizationToken(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = auth.substring(7).trim();
            return StringUtils.hasText(token) ? token : null;
        }
        return null;
    }
}
