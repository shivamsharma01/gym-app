package com.example.gym.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.gym.device.domain.Gateway;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;

@ExtendWith(MockitoExtension.class)
class GatewayHandshakeInterceptorTest {

    @Mock
    private GatewayAuthService authService;

    @Mock
    private WebSocketHandler wsHandler;

    @Mock
    private ServerHttpResponse response;

    private GatewayHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new GatewayHandshakeInterceptor(authService);
    }

    @Test
    void missingTokenRejectsTheHandshake() {
        when(authService.authenticate(null)).thenReturn(Optional.empty());
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setParameter("gatewayId", "gw-claimed");

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servlet), response, wsHandler, attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).doesNotContainKey(GatewayHandshakeInterceptor.GATEWAY_ID_ATTR);
    }

    @Test
    void expiredCredentialRejectsTheHandshake() {
        when(authService.authenticate("expired")).thenReturn(Optional.empty());
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.addHeader("Authorization", "Bearer expired");
        servlet.setParameter("gatewayId", "gw-claimed");

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servlet), response, wsHandler, attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    void sessionIsBoundToTheCredentialNotTheQueryGatewayId() {
        Gateway gateway = new Gateway(1L, "Front", "hash");
        gateway.setPublicId("gw-real");
        when(authService.authenticate("secret")).thenReturn(Optional.of(gateway));
        when(authService.hash("secret")).thenReturn("hashed-secret");
        when(authService.sessionAllows(gateway, "hashed-secret")).thenReturn(true);
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.addHeader("Authorization", "Bearer secret");
        servlet.setParameter("gatewayId", "gw-other");

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servlet), response, wsHandler, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(GatewayHandshakeInterceptor.GATEWAY_ID_ATTR, "gw-real");
        assertThat(attributes).containsEntry(GatewayHandshakeInterceptor.CREDENTIAL_HASH_ATTR, "hashed-secret");
    }

    @Test
    void queryTokenIsNotAccepted() {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setParameter("token", "secret");

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servlet), response, wsHandler, attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).isEmpty();
        verify(authService, never()).authenticate(any());
    }

    @Test
    void queryTokenIsRejectedEvenWhenAuthorizationIsPresent() {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.addHeader("Authorization", "Bearer secret");
        servlet.setParameter("token", "secret");

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servlet), response, wsHandler, attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).isEmpty();
        verify(authService, never()).authenticate(any());
    }

    @Test
    void rotatedCredentialIsRejectedForANewHandshake() {
        Gateway gateway = new Gateway(1L, "Front", "old-hash");
        gateway.setPublicId("gw-real");
        when(authService.authenticate("old")).thenReturn(Optional.of(gateway));
        when(authService.hash("old")).thenReturn("old-hash");
        when(authService.sessionAllows(gateway, "old-hash")).thenReturn(false);
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.addHeader("Authorization", "Bearer old");

        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servlet), response, wsHandler, attributes);

        assertThat(accepted).isFalse();
        assertThat(attributes).isEmpty();
    }
}
