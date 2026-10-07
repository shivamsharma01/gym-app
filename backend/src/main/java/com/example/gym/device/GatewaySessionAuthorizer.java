package com.example.gym.device;

import org.springframework.web.socket.WebSocketSession;

/**
 * Decides whether a gateway WebSocket bound at handshake may still send or receive.
 * Expiry and credential rotation must make this return false.
 */
@FunctionalInterface
public interface GatewaySessionAuthorizer {

    boolean allow(WebSocketSession session);
}
