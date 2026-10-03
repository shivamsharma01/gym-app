package com.example.gym.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.handler.TextWebSocketHandler;

class StaffLiveHandshakeInterceptorTest {

    @Test
    void readsBearerAuthorizationHeader() {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.addHeader(HttpHeaders.AUTHORIZATION, "Bearer staff-jwt");
        servlet.setParameter("access_token", "query-jwt");

        String token = StaffLiveHandshakeInterceptor.presentedToken(new ServletServerHttpRequest(servlet));

        assertThat(token).isEqualTo("staff-jwt");
    }

    @Test
    void readsJwtFromWebSocketSubprotocolAndIgnoresQuery() {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.addHeader("Sec-WebSocket-Protocol", "bearer, eyJhbGciOiJ");
        servlet.setParameter("access_token", "query-jwt");

        String token = StaffLiveHandshakeInterceptor.presentedToken(new ServletServerHttpRequest(servlet));

        assertThat(token).isEqualTo("eyJhbGciOiJ");
    }

    @Test
    void rejectsAmbiguousSubprotocols() {
        assertThat(StaffLiveHandshakeInterceptor.protocolToken(List.of("bearer, one, two"))).isNull();
    }

    @Test
    void echoesOnlyBearerProtocol() {
        StaffLiveHandshakeHandler handler = new StaffLiveHandshakeHandler();
        String selected = handler.selectProtocol(List.of("eyJhbGciOiJ", "bearer"), new TextWebSocketHandler() {});

        assertThat(selected).isEqualTo("bearer");
    }
}
