package com.example.gym.device;

/** A reader confirmed a CREATE_USER for a member under {@code deviceUserId}. */
public record DeviceUserCreated(Long deviceId, Long memberId, String deviceUserId) {
}
