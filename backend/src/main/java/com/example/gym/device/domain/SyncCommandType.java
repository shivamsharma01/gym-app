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
    /** Legacy guided enrolment; superseded by {@link #UPSERT_FACE}. */
    ENROLL_FACE,
    /** Push the member's stored face photo (payload: deviceUserId, memberId, faceVersion, sha256). */
    UPSERT_FACE,
    DELETE_FACE,
    SYNC_DEVICE_TIME,
    OPEN_DOOR,
    CLOSE_DOOR,
    REFRESH_DEVICE_USERS,
    /** Ask the gateway to send one device user's profile + face as DEVICE_USER_CHANGED. */
    REPORT_DEVICE_USER,
    RECONCILE_DEVICE,
    CLEAR_DEVICE_LOGS
}
