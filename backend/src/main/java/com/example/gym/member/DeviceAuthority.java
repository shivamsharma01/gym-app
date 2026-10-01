package com.example.gym.member;

public enum DeviceAuthority {
    USER,
    ADMIN;

    public static DeviceAuthority fromString(String value) {
        if (value == null || value.isBlank()) {
            return USER;
        }
        try {
            return DeviceAuthority.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }
}
