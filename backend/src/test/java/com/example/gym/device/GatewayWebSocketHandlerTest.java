package com.example.gym.device;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class GatewayWebSocketHandlerTest {

    @Mock
    private GatewayMessageService messageService;

    @Mock
    private GatewaySessionRegistry registry;

    @Mock
    private GatewayService gatewayService;

    @Mock
    private GatewaySessionAuthorizer authorizer;

    @Mock
    private WebSocketSession session;

    private GatewayWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GatewayWebSocketHandler(messageService, registry, gatewayService, authorizer);
    }

    @Test
    void unboundSessionCannotRegisterFromTheMessageBody() throws Exception {
        when(authorizer.allow(session)).thenReturn(false);
        String raw = "{\"type\":\"REGISTER_GATEWAY\",\"gatewayId\":\"gw-claimed\",\"payload\":{}}";

        handler.handleTextMessage(session, new TextMessage(raw));

        verify(messageService, never()).process(anyString(), any());
        verify(registry, never()).register(anyString(), any());
        verify(registry, never()).reply(any(), anyString());
        verify(registry).invalidate(session);
    }

    @Test
    void invalidatedSessionDoesNotProcessOrReply() throws Exception {
        when(authorizer.allow(session)).thenReturn(false);
        String raw = "{\"type\":\"SYNC_RESULT\",\"gatewayId\":\"gw-a\",\"deviceId\":\"dev-b\"}";

        handler.handleTextMessage(session, new TextMessage(raw));

        verify(messageService, never()).process(anyString(), any());
        verify(registry, never()).reply(any(), anyString());
        verify(registry).invalidate(session);
    }

    @Test
    void messageIsHandledAsTheHandshakeGateway() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(GatewayHandshakeInterceptor.GATEWAY_ID_ATTR, "gw-a");
        attributes.put(GatewayHandshakeInterceptor.CREDENTIAL_HASH_ATTR, "hash");
        when(session.getAttributes()).thenReturn(attributes);
        when(authorizer.allow(session)).thenReturn(true);
        when(messageService.process(anyString(), eq("gw-a"))).thenReturn(Optional.of("{\"type\":\"ACK\"}"));
        String raw = "{\"type\":\"HEARTBEAT\",\"gatewayId\":\"gw-b\"}";

        handler.handleTextMessage(session, new TextMessage(raw));

        verify(messageService).process(raw, "gw-a");
        verify(registry, never()).register(eq("gw-b"), any());
        verify(registry).reply(session, "{\"type\":\"ACK\"}");
    }
}
