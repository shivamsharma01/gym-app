package com.example.gym.device;

import com.example.gym.device.domain.Gateway;
import com.example.gym.device.repo.GatewayRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

/**
 * Re-checks the credential a socket was bound to. The raw token is not stored on the session;
 * only its hash is, and that hash is compared with the current gateway row.
 */
@Component
public class GatewayConnectionGuard implements GatewaySessionAuthorizer {

    private final GatewayRepository gatewayRepository;
    private final GatewayAuthService authService;

    public GatewayConnectionGuard(GatewayRepository gatewayRepository, GatewayAuthService authService) {
        this.gatewayRepository = gatewayRepository;
        this.authService = authService;
    }

    @Override
    public boolean allow(WebSocketSession session) {
        if (session == null) {
            return false;
        }
        Object gatewayId = session.getAttributes().get(GatewayHandshakeInterceptor.GATEWAY_ID_ATTR);
        Object credentialHash = session.getAttributes().get(GatewayHandshakeInterceptor.CREDENTIAL_HASH_ATTR);
        if (gatewayId == null || credentialHash == null
                || !StringUtils.hasText(gatewayId.toString())
                || !StringUtils.hasText(credentialHash.toString())) {
            return false;
        }
        Gateway gateway = gatewayRepository.findByPublicId(gatewayId.toString()).orElse(null);
        return authService.sessionAllows(gateway, credentialHash.toString());
    }
}
