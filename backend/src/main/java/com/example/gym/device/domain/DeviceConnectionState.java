package com.example.gym.device.domain;

/**
 * Connectivity of a reader as last reported by its gym PC. Staff only see Online while that
 * gym PC is itself online; the last report is kept for when the PC returns.
 */
public enum DeviceConnectionState {
    ONLINE,
    OFFLINE,
    UNKNOWN
}
