package com.example.gym.device.dto;

/**
 * One open review row: the server value, the reader value, and the last baseline.
 * {@code id} is the public id. No numeric ids are exposed.
 */
public record ReviewItemView(
        String id,
        String kind,
        String deviceId,
        String deviceUserId,
        String serverName,
        String readerName,
        String baselineName,
        boolean readerAbsent,
        boolean open,
        String decision,
        String actor,
        String priorState,
        String chosenState,
        Long revision,
        String verificationError) {
}
