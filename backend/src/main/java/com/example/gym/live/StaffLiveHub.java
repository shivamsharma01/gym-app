package com.example.gym.live;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

@Component
public class StaffLiveHub {

    static final String TENANT_ATTR = "staffTenantId";

    private static final Logger log = LoggerFactory.getLogger(StaffLiveHub.class);

    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final Set<WebSocketSession> platformSessions = ConcurrentHashMap.newKeySet();
    private final JsonMapper jsonMapper;

    public StaffLiveHub(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void register(WebSocketSession session) {
        Long tenantId = tenantId(session);
        if (tenantId == null) {
            platformSessions.add(session);
        } else {
            sessions.computeIfAbsent(tenantId, id -> new CopyOnWriteArraySet<>()).add(session);
        }
    }

    public void unregister(WebSocketSession session) {
        Long tenantId = tenantId(session);
        if (tenantId == null) {
            platformSessions.remove(session);
        } else {
            Set<WebSocketSession> set = sessions.get(tenantId);
            if (set != null) {
                set.remove(session);
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBroadcast(StaffLiveBroadcast event) {
        String json;
        try {
            json = jsonMapper.writeValueAsString(Map.of(
                    "type", event.type(),
                    "payload", event.payload() == null ? Map.of() : event.payload()));
        } catch (RuntimeException ex) {
            log.warn("Could not serialize live event {}", event.type(), ex);
            return;
        }
        sendAll(platformSessions, json);
        if (event.tenantId() != null) {
            sendAll(sessions.getOrDefault(event.tenantId(), Set.of()), json);
        }
    }

    private void sendAll(Set<WebSocketSession> targets, String json) {
        for (WebSocketSession session : targets) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(new TextMessage(json));
            } catch (IOException ex) {
                log.debug("Live push failed for session {}", session.getId());
            }
        }
    }

    private static Long tenantId(WebSocketSession session) {
        Object value = session.getAttributes().get(TENANT_ATTR);
        return value instanceof Long tenantId ? tenantId : null;
    }
}
