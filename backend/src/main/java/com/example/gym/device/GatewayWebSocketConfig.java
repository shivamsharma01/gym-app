package com.example.gym.device;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the gateway WSS endpoint at {@code /gateway}. The device gateway connects outbound to
 * this endpoint (NAT-friendly); it is authenticated by {@link GatewayHandshakeInterceptor}, not the
 * user JWT filter.
 */
@Configuration
@EnableWebSocket
public class GatewayWebSocketConfig implements WebSocketConfigurer {

    private final GatewayWebSocketHandler handler;
    private final GatewayHandshakeInterceptor handshakeInterceptor;

    public GatewayWebSocketConfig(GatewayWebSocketHandler handler,
                                  GatewayHandshakeInterceptor handshakeInterceptor) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/gateway")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
