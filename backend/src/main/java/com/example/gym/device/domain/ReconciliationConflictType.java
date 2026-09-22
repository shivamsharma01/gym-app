package com.example.gym.device.domain;

/** Why a device user list differs from the server's desired authorization state. */
public enum ReconciliationConflictType {
    /** User exists on the device but has no member_device_mapping. */
    EXTRA_DEVICE_USER,
    /** Mapping exists on the server but the user is missing on the device. */
    MISSING_ON_DEVICE,
    /** User exists on both sides but freeze/validity does not match desired authorization. */
    AUTH_MISMATCH
}
