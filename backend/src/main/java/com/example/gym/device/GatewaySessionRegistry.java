package com.example.gym.device;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Tracks live gateway WebSocket sessions keyed by gateway public id. Every write to a gateway
 * (outbox commands from the dispatcher thread, replies from the receiving thread) goes through one
 * thread-safe decorator per session: concurrent sends are queued instead of failing, and a send
 * that cannot complete within {@link #SEND_TIME_LIMIT_MS} closes the session rather than blocking
 * the outbox loop.
 */
@Component
public class GatewaySessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(GatewaySessionRegistry.class);
    static final int SEND_TIME_LIMIT_MS = 10_000;
    static final int SEND_BUFFER_LIMIT_BYTES = 8 * 1024 * 1024;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> decorated = new ConcurrentHashMap<>();

    public void register(String gatewayPublicId, WebSocketSession session) {
        sessions.put(gatewayPublicId, safe(session));
    }

    public void removeBySession(WebSocketSession session) {
        sessions.values().removeIf(s -> s.getId().equals(session.getId()));
        decorated.remove(session.getId());
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
        return write(session, text, gatewayPublicId);
    }

    /** Replies on the session a message arrived on, through the same thread-safe writer as commands. */
    public boolean reply(WebSocketSession session, String text) {
        WebSocketSession target = safe(session);
        return target.isOpen() && write(target, text, session.getId());
    }

    private WebSocketSession safe(WebSocketSession session) {
        if (session instanceof ConcurrentWebSocketSessionDecorator) {
            return session;
        }
        return decorated.computeIfAbsent(session.getId(), id ->
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
    }

    /**
     * Cloudflare closes an idle WebSocket after about 100 seconds without a close frame, which the
     * gateway reports as "session ended". A ping counts as traffic and is answered by the client stack.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void pingOpenSessions() {
        for (WebSocketSession session : sessions.values()) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(new PingMessage());
            } catch (IOException | RuntimeException ex) {
                log.debug("Gateway ping failed for session {}: {}", session.getId(), ex.getMessage());
            }
        }
    }

    private static boolean write(WebSocketSession session, String text, String target) {
        try {
            session.sendMessage(new TextMessage(text));
            return true;
        } catch (IOException | RuntimeException ex) {
            log.warn("Failed to send to gateway {}: {}", target, ex.getMessage());
            return false;
        }
    }
}
