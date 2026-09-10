package com.example.gym.device.domain;

/**
 * Connectivity of a device as reported by its gateway. Deliberately distinct from "backend
 * reachable" — the UI must not conflate the two (§11): a standalone device can keep verifying
 * locally while offline from the gateway/backend.
 */
public enum DeviceConnectionState {
    ONLINE,
    OFFLINE,
    UNKNOWN
}
