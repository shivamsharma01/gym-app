package com.example.gym.device;

import com.example.gym.device.domain.DeviceSyncCommand;

/**
 * Hands a sync command off to the device gateway. Implementations must not perform device I/O
 * themselves (that is the gateway's job); they only deliver the command over the gateway link.
 */
public interface GatewayCommandTransport {

    enum Outcome {
        /** Written to the gateway connection. */
        SENT,
        /** The gateway is offline; the command waits for it without using up delivery attempts. */
        NOT_CONNECTED,
        /** Delivery was attempted and failed (or can never succeed, e.g. no gateway assigned). */
        FAILED
    }

    Outcome dispatch(DeviceSyncCommand command);
}
