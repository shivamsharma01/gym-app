package com.example.gym.device.protocol;

/** The gateway ↔ backend message vocabulary (§37). */
public enum GatewayMessageType {
    // Gateway -> Backend
    REGISTER_GATEWAY,
    HEARTBEAT,
    DEVICE_STATUS,
    DEVICE_METADATA,
    DEVICE_EVENT,
    DEVICE_ALARM,
    SYNC_RESULT,
    RECONCILIATION_RESULT,
    ENROLLMENT_RESULT,
    /** A user (or their face) was created/changed directly on a device. */
    DEVICE_USER_CHANGED,

    // Backend -> Gateway replies
    REGISTERED,
    ACK,
    ERROR

    // Backend -> Gateway commands use SyncCommandType names in the same envelope
    // (CREATE_USER, DISABLE_USER, UPDATE_VALIDITY, OPEN_DOOR, RECONCILE_DEVICE, ...).
}
