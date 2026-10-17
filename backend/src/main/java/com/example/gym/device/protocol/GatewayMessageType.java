package com.example.gym.device.protocol;

/** The gateway ↔ backend message vocabulary (§37). */
public enum GatewayMessageType {
    REGISTER_GATEWAY,
    HEARTBEAT,
    DEVICE_STATUS,
    DEVICE_METADATA,
    DEVICE_ALARM,
    SYNC_RESULT,
    /** A user (or their face) was created/changed directly on a device. */
    DEVICE_USER_CHANGED,

    REGISTERED,
    ACK,
    ERROR

    // Backend -> Gateway commands use SyncCommandType names in the same envelope
    // (OPEN_DOOR, CLOSE_DOOR, SCAN_READER).
}
