package com.example.gym.device;

import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A reader already showing this member's photo, under that reader's own user id, with no mapping yet.
 * Same name is not enough. The id is not claimed when another member already holds it.
 */
@Component
public class ReaderFacePresence {

    private final MemberFaceRepository faces;
    private final DeviceObservedUserRepository observedUsers;
    private final MemberDeviceMappingRepository mappings;

    public ReaderFacePresence(MemberFaceRepository faces,
                              DeviceObservedUserRepository observedUsers,
                              MemberDeviceMappingRepository mappings) {
        this.faces = faces;
        this.observedUsers = observedUsers;
        this.mappings = mappings;
    }

    public record Sight(String deviceUserId, String validFrom, String validTo, boolean facePresent) {
    }

    /** Member id, then device id, of the reader id to show. Empty when nobody in the set has a matching photo. */
    public Map<Long, Map<Long, Sight>> forMembers(Long tenantId, Collection<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Long>> membersByFace = new HashMap<>();
        for (MemberFace face : faces.findByMemberIdIn(memberIds)) {
            String hash = hash(face.getSha256());
            if (hash == null) {
                continue;
            }
            membersByFace.computeIfAbsent(hash, key -> new java.util.ArrayList<>()).add(face.getMemberId());
        }
        if (membersByFace.isEmpty()) {
            return Map.of();
        }
        List<DeviceObservedUser> observed = observedUsers.findByTenantIdAndFaceSha256In(tenantId, membersByFace.keySet());
        if (observed.isEmpty()) {
            return Map.of();
        }
        List<Long> deviceIds = observed.stream().map(DeviceObservedUser::getDeviceId).distinct().toList();
        List<String> userIds = observed.stream().map(DeviceObservedUser::getDeviceUserId).distinct().toList();
        Map<String, Long> heldBy = new HashMap<>();
        for (MemberDeviceMapping mapping : mappings.findByDeviceIdInAndDeviceUserIdIn(deviceIds, userIds)) {
            heldBy.put(key(mapping.getDeviceId(), mapping.getDeviceUserId()), mapping.getMemberId());
            if (mapping.getPendingDeviceUserId() != null) {
                heldBy.putIfAbsent(key(mapping.getDeviceId(), mapping.getPendingDeviceUserId()), mapping.getMemberId());
            }
        }
        Map<Long, Map<Long, Sight>> out = new HashMap<>();
        for (DeviceObservedUser person : observed) {
            String hash = hash(person.getFaceSha256());
            if (hash == null) {
                continue;
            }
            for (Long memberId : membersByFace.getOrDefault(hash, List.of())) {
                Long holder = heldBy.get(key(person.getDeviceId(), person.getDeviceUserId()));
                if (holder != null) {
                    continue;
                }
                out.computeIfAbsent(memberId, id -> new HashMap<>())
                        .merge(person.getDeviceId(), sight(person), ReaderFacePresence::prefer);
            }
        }
        return out;
    }

    private static Sight prefer(Sight current, Sight candidate) {
        return compareIds(candidate.deviceUserId(), current.deviceUserId()) < 0 ? candidate : current;
    }

    private static Sight sight(DeviceObservedUser person) {
        String hash = hash(person.getFaceSha256());
        return new Sight(person.getDeviceUserId(), person.getValidFrom(), person.getValidTo(), hash != null);
    }

    private static String hash(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.length() == 64 ? trimmed : null;
    }

    private static String key(Long deviceId, String deviceUserId) {
        return deviceId + "\n" + deviceUserId;
    }

    private static int compareIds(String left, String right) {
        Long a = number(left);
        Long b = number(right);
        if (a != null && b != null && !a.equals(b)) {
            return Long.compare(a, b);
        }
        return left.compareTo(right);
    }

    private static Long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
