package com.example.gym.device;

import com.example.gym.live.StaffLiveHandshakeInterceptor;
import com.example.gym.live.StaffLiveWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the gateway WSS endpoint at {@code /gateway} and the staff live endpoint at {@code /live}.
 */
@Configuration
@EnableWebSocket
public class GatewayWebSocketConfig implements WebSocketConfigurer {

    private final GatewayWebSocketHandler handler;
    private final GatewayHandshakeInterceptor handshakeInterceptor;
    private final StaffLiveWebSocketHandler staffLiveHandler;
    private final StaffLiveHandshakeInterceptor staffLiveHandshake;

    public GatewayWebSocketConfig(GatewayWebSocketHandler handler,
                                  GatewayHandshakeInterceptor handshakeInterceptor,
                                  StaffLiveWebSocketHandler staffLiveHandler,
                                  StaffLiveHandshakeInterceptor staffLiveHandshake) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
        this.staffLiveHandler = staffLiveHandler;
        this.staffLiveHandshake = staffLiveHandshake;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/gateway")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
        registry.addHandler(staffLiveHandler, "/live")
                .addInterceptors(staffLiveHandshake)
                .setAllowedOriginPatterns("*");
    }
}
