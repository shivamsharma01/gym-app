package com.example.gym.device.dto;

/**
 * One open or decided item: a member's record on one reader (a review), or a person a reader shows
 * that the server does not know (an enrollment). {@code memberId} groups one member's items across
 * readers; {@code possibleMemberId} is a member an enrollment may already be, never linked on its own.
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
        String verificationError,
        String serverValidFrom,
        String serverValidTo,
        String readerValidFrom,
        String readerValidTo,
        String baselineValidFrom,
        String baselineValidTo,
        Integer serverUserStatus,
        Integer readerUserStatus,
        Integer baselineUserStatus,
        boolean readerPhotoDiffers,
        String deviceName,
        String memberId,
        String memberName,
        String possibleMemberId,
        String possibleMemberName) {

    public ReviewItemView withPossibleMember(String memberPublicId, String name) {
        return new ReviewItemView(id, kind, deviceId, deviceUserId, serverName, readerName, baselineName,
                readerAbsent, open, decision, actor, priorState, chosenState, revision, verificationError,
                serverValidFrom, serverValidTo, readerValidFrom, readerValidTo, baselineValidFrom,
                baselineValidTo, serverUserStatus, readerUserStatus, baselineUserStatus, readerPhotoDiffers,
                deviceName, memberId, memberName, memberPublicId, name);
    }
}
