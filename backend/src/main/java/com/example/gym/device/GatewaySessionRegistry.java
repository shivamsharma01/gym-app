package com.example.gym.device;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
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

    private final GatewaySessionAuthorizer authorizer;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> decorated = new ConcurrentHashMap<>();

    public GatewaySessionRegistry(GatewaySessionAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    public void register(String gatewayPublicId, WebSocketSession session) {
        WebSocketSession previous = sessions.put(gatewayPublicId, safe(session));
        log.info("Gateway {} registered for commands on session {}{}", gatewayPublicId, session.getId(),
                previous == null || previous.getId().equals(session.getId())
                        ? "" : " (replaces session " + previous.getId() + ")");
    }

    public void removeBySession(WebSocketSession session) {
        if (sessions.values().removeIf(s -> s.getId().equals(session.getId()))) {
            log.info("Gateway session {} removed; commands for its devices wait until it reconnects", session.getId());
        }
        decorated.remove(session.getId());
    }

    /** Closes and forgets a session whose credential is no longer valid. */
    public void invalidate(WebSocketSession session) {
        if (session != null) {
            drop(session);
        }
    }

    /** Gateway public ids with an open session whose credential is still valid. */
    public Set<String> connectedGateways() {
        Set<String> open = new TreeSet<>();
        for (var entry : new ArrayList<>(sessions.entrySet())) {
            if (live(entry.getValue())) {
                open.add(entry.getKey());
            }
        }
        return open;
    }

    public boolean isOnline(String gatewayPublicId) {
        return live(sessions.get(gatewayPublicId));
    }

    /** Sends text to a gateway; returns false when the gateway is not connected or the send fails. */
    public boolean send(String gatewayPublicId, String text) {
        WebSocketSession session = sessions.get(gatewayPublicId);
        if (!live(session)) {
            return false;
        }
        return write(session, text, gatewayPublicId);
    }

    /** Replies on the session a message arrived on, through the same thread-safe writer as commands. */
    public boolean reply(WebSocketSession session, String text) {
        if (session == null || !authorizer.allow(session)) {
            invalidate(session);
            return false;
        }
        WebSocketSession target = safe(session);
        return target.isOpen() && write(target, text, session.getId());
    }

    /**
     * An open socket whose credential has expired or been rotated is not live. Dropping it keeps
     * command pushes and online checks from treating it as a connected gateway.
     */
    private boolean live(WebSocketSession session) {
        if (session == null || !session.isOpen()) {
            return false;
        }
        if (authorizer.allow(session)) {
            return true;
        }
        drop(session);
        return false;
    }

    private void drop(WebSocketSession session) {
        removeBySession(session);
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException | RuntimeException ex) {
            log.debug("Closing invalidated gateway session {}: {}", session.getId(), ex.getMessage());
        }
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
        for (WebSocketSession session : new ArrayList<>(sessions.values())) {
            if (!live(session)) {
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
