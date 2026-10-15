package com.example.gym.device;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Server side of the gateway WSS link. The session is bound by
 * {@link GatewayHandshakeInterceptor} to a gateway and a credential hash. Each message is accepted
 * only while that credential is still current. Message bodies cannot register or rename the session.
 * Delegates message semantics to {@link GatewayMessageService}.
 */
@Component
public class GatewayWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebSocketHandler.class);
    private static final String GATEWAY_ID_ATTR = GatewayHandshakeInterceptor.GATEWAY_ID_ATTR;
    static final int MAX_TEXT_MESSAGE_BYTES = 1024 * 1024;

    private final GatewayMessageService messageService;
    private final GatewaySessionRegistry registry;
    private final GatewayService gatewayService;
    private final GatewaySessionAuthorizer authorizer;

    public GatewayWebSocketHandler(GatewayMessageService messageService,
                                   GatewaySessionRegistry registry,
                                   GatewayService gatewayService,
                                   GatewaySessionAuthorizer authorizer) {
        this.messageService = messageService;
        this.registry = registry;
        this.gatewayService = gatewayService;
        this.authorizer = authorizer;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Reconcile results carry full rosters; the container default (~8 KB) would close the socket.
        session.setTextMessageSizeLimit(MAX_TEXT_MESSAGE_BYTES);
        if (!authorizer.allow(session)) {
            registry.invalidate(session);
            return;
        }
        Object gatewayId = session.getAttributes().get(GATEWAY_ID_ATTR);
        if (gatewayId != null && StringUtils.hasText(gatewayId.toString())) {
            registry.register(gatewayId.toString(), session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!authorizer.allow(session)) {
            registry.invalidate(session);
            return;
        }
        String raw = message.getPayload();
        Object bound = session.getAttributes().get(GATEWAY_ID_ATTR);
        Optional<String> reply = messageService.process(raw, bound.toString());
        reply.ifPresent(text -> registry.reply(session, text));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Object gatewayId = session.getAttributes().get(GATEWAY_ID_ATTR);
        log.warn("Gateway websocket transport error gateway={} session={}: {}",
                gatewayId, session.getId(), exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object closedGatewayId = session.getAttributes().get(GATEWAY_ID_ATTR);
        log.info("Gateway websocket closed gateway={} code={} reason={}",
                closedGatewayId, status.getCode(), status.getReason());
        registry.removeBySession(session);
        Object gatewayId = session.getAttributes().get(GATEWAY_ID_ATTR);
        if (gatewayId != null) {
            gatewayService.markOffline(gatewayId.toString());
        }
    }

}
