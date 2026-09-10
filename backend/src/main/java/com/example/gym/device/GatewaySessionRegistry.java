package com.example.gym.device;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Tracks live gateway WebSocket sessions keyed by gateway public id. */
@Component
public class GatewaySessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(GatewaySessionRegistry.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void register(String gatewayPublicId, WebSocketSession session) {
        sessions.put(gatewayPublicId, session);
    }

    public void removeBySession(WebSocketSession session) {
        sessions.values().removeIf(s -> s.getId().equals(session.getId()));
    }

    public boolean isOnline(String gatewayPublicId) {
        WebSocketSession session = sessions.get(gatewayPublicId);
        return session != null && session.isOpen();
    }

    /** Sends text to a gateway; returns false when the gateway is not connected or the send fails. */
    public boolean send(String gatewayPublicId, String text) {
        WebSocketSession session = sessions.get(gatewayPublicId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
            return true;
        } catch (IOException ex) {
            log.warn("Failed to send to gateway {}: {}", gatewayPublicId, ex.getMessage());
            return false;
        }
    }
}
