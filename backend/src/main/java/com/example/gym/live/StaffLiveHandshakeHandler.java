package com.example.gym.live;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Echoes only {@code bearer}. The JWT is presented as a second subprotocol and must not be
 * reflected in the handshake response.
 */
@Component
public class StaffLiveHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected String selectProtocol(List<String> requestedProtocols, WebSocketHandler webSocketHandler) {
        if (requestedProtocols == null) {
            return null;
        }
        for (String raw : requestedProtocols) {
            if (raw == null) {
                continue;
            }
            for (String part : raw.split(",")) {
                if (StaffLiveHandshakeInterceptor.BEARER_PROTOCOL.equalsIgnoreCase(part.trim())) {
                    return StaffLiveHandshakeInterceptor.BEARER_PROTOCOL;
                }
            }
        }
        return null;
    }
}
