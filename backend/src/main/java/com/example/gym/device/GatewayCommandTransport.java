package com.example.gym.device;

import com.example.gym.device.domain.DeviceSyncCommand;

/**
 * Hands a sync command off to the device gateway. Implementations must not perform device I/O
 * themselves (that is the gateway's job); they only deliver the command over the gateway link.
 */
public interface GatewayCommandTransport {

    /** @return true if the command was delivered to a connected gateway; false if none is reachable. */
    boolean dispatch(DeviceSyncCommand command);
}
