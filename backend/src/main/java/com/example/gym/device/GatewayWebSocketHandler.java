package com.example.gym.device;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Server side of the gateway WSS link. Delegates all message semantics to
 * {@link GatewayMessageService} and only manages session lifecycle/registration here.
 */
@Component
public class GatewayWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebSocketHandler.class);
    private static final String GATEWAY_ID_ATTR = GatewayHandshakeInterceptor.GATEWAY_ID_ATTR;
    static final int MAX_TEXT_MESSAGE_BYTES = 1024 * 1024;

    private final GatewayMessageService messageService;
    private final GatewaySessionRegistry registry;
    private final GatewayService gatewayService;
    private final JsonMapper jsonMapper;

    public GatewayWebSocketHandler(GatewayMessageService messageService,
                                   GatewaySessionRegistry registry,
                                   GatewayService gatewayService,
                                   JsonMapper jsonMapper) {
        this.messageService = messageService;
        this.registry = registry;
        this.gatewayService = gatewayService;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Reconcile results carry full rosters; the container default (~8 KB) would close the socket.
        session.setTextMessageSizeLimit(MAX_TEXT_MESSAGE_BYTES);
        Object gatewayId = session.getAttributes().get(GATEWAY_ID_ATTR);
        if (gatewayId != null) {
            registry.register(gatewayId.toString(), session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String raw = message.getPayload();
        if (!registerIfHandshake(session, raw)) {
            registry.reply(session, messageService.impersonationError());
            return;
        }
        Optional<String> reply = messageService.process(raw);
        reply.ifPresent(text -> registry.reply(session, text));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.removeBySession(session);
        Object gatewayId = session.getAttributes().get(GATEWAY_ID_ATTR);
        if (gatewayId != null) {
            gatewayService.markOffline(gatewayId.toString());
        }
    }

    /**
     * On REGISTER_GATEWAY, bind the session to the gateway id for command delivery. Returns false
     * when the message tries to impersonate a different gateway than the handshake bound.
     */
    private boolean registerIfHandshake(WebSocketSession session, String raw) {
        try {
            JsonNode node = jsonMapper.readTree(raw);
            String gatewayId = text(node, "gatewayId");
            Object bound = session.getAttributes().get(GATEWAY_ID_ATTR);
            if (bound != null && gatewayId != null && !bound.toString().equals(gatewayId)) {
                log.warn("Rejecting gateway message: session bound to a different gateway");
                return false;
            }
            if (gatewayId != null && bound == null) {
                session.getAttributes().put(GATEWAY_ID_ATTR, gatewayId);
                registry.register(gatewayId, session);
            }
            return true;
        } catch (RuntimeException ex) {
            log.debug("Could not inspect message for registration: {}", ex.getMessage());
            return true;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
