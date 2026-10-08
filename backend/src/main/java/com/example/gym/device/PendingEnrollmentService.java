package com.example.gym.device;

import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Stores a reader-created person as one pending enrollment. It does not create a member and it does
 * not allocate or change the device user id.
 */
@Service
public class PendingEnrollmentService {

    private final DeviceObservedUserRepository observedUsers;
    private final PendingEnrollmentRepository enrollments;
    private final MemberDeviceMappingRepository mappings;
    private final DesiredMemberProjectionRepository desiredMembers;

    public PendingEnrollmentService(DeviceObservedUserRepository observedUsers,
                                    PendingEnrollmentRepository enrollments,
                                    MemberDeviceMappingRepository mappings,
                                    DesiredMemberProjectionRepository desiredMembers) {
        this.observedUsers = observedUsers;
        this.enrollments = enrollments;
        this.mappings = mappings;
        this.desiredMembers = desiredMembers;
    }

    @Transactional
    public void observe(Device device, JsonNode payload) {
        if (device == null || !device.isProjectionEnabled() || payload == null || bool(payload, "deleted")) {
            return;
        }
        String deviceUserId = text(payload, "deviceUserId");
        if (deviceUserId == null || deviceUserId.isBlank()) {
            return;
        }
        if (serverAllocated(device.getId(), deviceUserId)) {
            return;
        }

        Instant observedAt = Instant.now();
        DeviceObservedUser snapshot = observedUsers.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElseGet(() -> new DeviceObservedUser(device.getTenantId(), device.getId(), deviceUserId));
        snapshot.setReaderName(cut(text(payload, "name"), 127));
        snapshot.setReaderNameEx(cut(text(payload, "nameEx"), 127));
        snapshot.setUserStatus(number(payload, "userStatus"));
        snapshot.setValidFrom(cut(text(payload, "validFrom"), 40));
        snapshot.setValidTo(cut(text(payload, "validTo"), 40));
        snapshot.setAuthority(cut(text(payload, "authority"), 32));
        snapshot.setFaceSha256(face(text(payload, "faceSha256")));
        snapshot.setObservedAt(observedAt);
        observedUsers.save(snapshot);

        PendingEnrollment enrollment = enrollments.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElseGet(() -> new PendingEnrollment(device.getTenantId(), device.getId(), deviceUserId));
        enrollment.setObservedAt(observedAt);
        enrollments.save(enrollment);
    }

    private boolean serverAllocated(Long deviceId, String deviceUserId) {
        if (mappings.existsByDeviceIdAndDeviceUserId(deviceId, deviceUserId)) {
            return true;
        }
        if (mappings.findFirstByDeviceIdAndPendingDeviceUserId(deviceId, deviceUserId).isPresent()) {
            return true;
        }
        Optional<DesiredMemberProjection> desired =
                desiredMembers.findByDeviceIdAndDeviceUserId(deviceId, deviceUserId);
        return desired.isPresent();
    }

    private static String face(String value) {
        return value != null && value.length() == 64 ? value : null;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static int number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? 0 : value.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static boolean bool(JsonNode node, String field) {
        return node.has(field) && node.get(field).asBoolean();
    }
}
