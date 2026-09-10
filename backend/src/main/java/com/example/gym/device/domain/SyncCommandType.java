package com.example.gym.device.domain;

/** Device synchronization command types (§9). Each is idempotent on the device side. */
public enum SyncCommandType {
    CREATE_USER,
    UPDATE_USER,
    DISABLE_USER,
    ENABLE_USER,
    REMOVE_USER,
    UPDATE_VALIDITY,
    UPDATE_ACCESS_POLICY,
    ENROLL_FACE,
    DELETE_FACE,
    SYNC_DEVICE_TIME,
    OPEN_DOOR,
    CLOSE_DOOR,
    REFRESH_DEVICE_USERS,
    RECONCILE_DEVICE,
    CLEAR_DEVICE_LOGS
}
