package com.example.gym.device;

import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
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
    private final ReaderReviewService reviews;
    private final DeviceRepository devices;
    private final BootstrapReportService bootstrap;

    public PendingEnrollmentService(DeviceObservedUserRepository observedUsers,
                                    PendingEnrollmentRepository enrollments,
                                    MemberDeviceMappingRepository mappings,
                                    DesiredMemberProjectionRepository desiredMembers,
                                    ReaderReviewService reviews,
                                    DeviceRepository devices,
                                    BootstrapReportService bootstrap) {
        this.observedUsers = observedUsers;
        this.enrollments = enrollments;
        this.mappings = mappings;
        this.desiredMembers = desiredMembers;
        this.reviews = reviews;
        this.devices = devices;
        this.bootstrap = bootstrap;
    }

    @Transactional
    public void observe(Device device, JsonNode payload) {
        if (device == null || payload == null) {
            return;
        }
        JsonNode users = payload.get("users");
        if (users != null && users.isArray()) {
            observeSet(device, payload, users);
            return;
        }
        observeOne(device, payload);
    }

    /**
     * A short, empty, or mismatched list is not a disappearance and does not change the member.
     * A trusted list records a mapped id that is missing from it.
     */
    private void observeSet(Device device, JsonNode payload, JsonNode users) {
        int announced = payload.path("announcedTotal").asInt(-1);
        if (announced != users.size()) {
            rememberEmpty(device, false);
            return;
        }
        if (users.isEmpty()) {
            rememberEmpty(device, true);
            bootstrap.classifyTrustedEmpty(device);
            return;
        }
        rememberEmpty(device, false);
        Set<String> present = new HashSet<>();
        for (JsonNode user : users) {
            observeOne(device, user);
            String deviceUserId = text(user, "deviceUserId");
            if (deviceUserId != null) {
                present.add(deviceUserId);
            }
        }
        for (DesiredMemberProjection desired : desiredMembers.findByDeviceId(device.getId())) {
            if (desired.isPresentOnReader() && !present.contains(desired.getDeviceUserId())) {
                reviews.recordAbsence(device, desired.getDeviceUserId());
            }
        }
    }

    private void observeOne(Device device, JsonNode payload) {
        String deviceUserId = text(payload, "deviceUserId");
        if (deviceUserId == null || deviceUserId.isBlank()) {
            return;
        }
        if (bool(payload, "deleted")) {
            if (serverAllocated(device.getId(), deviceUserId)) {
                reviews.recordAbsence(device, deviceUserId);
            }
            return;
        }
        saveSnapshot(device, payload, deviceUserId);
        if (serverAllocated(device.getId(), deviceUserId)) {
            reviews.record(device, payload, deviceUserId);
            return;
        }

        Instant observedAt = Instant.now();

        PendingEnrollment enrollment = enrollments.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElseGet(() -> new PendingEnrollment(device.getTenantId(), device.getId(), deviceUserId));
        enrollment.setObservedAt(observedAt);
        enrollments.save(enrollment);
    }

    @Transactional(readOnly = true)
    public DeviceObservedUser snapshot(Long deviceId, String deviceUserId) {
        return observedUsers.findByDeviceIdAndDeviceUserId(deviceId, deviceUserId).orElse(null);
    }

    private void rememberEmpty(Device device, boolean trustedEmpty) {
        device.setRosterTrustedEmpty(trustedEmpty);
        devices.save(device);
    }

    private void saveSnapshot(Device device, JsonNode payload, String deviceUserId) {
        DeviceObservedUser snapshot = observedUsers.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElseGet(() -> new DeviceObservedUser(device.getTenantId(), device.getId(), deviceUserId));
        snapshot.setReaderName(cut(text(payload, "name"), 127));
        snapshot.setReaderNameEx(cut(text(payload, "nameEx"), 127));
        snapshot.setUserStatus(number(payload, "userStatus"));
        snapshot.setValidFrom(cut(text(payload, "validFrom"), 40));
        snapshot.setValidTo(cut(text(payload, "validTo"), 40));
        snapshot.setAuthority(cut(text(payload, "authority"), 32));
        snapshot.setFaceSha256(face(text(payload, "faceSha256")));
        snapshot.setObservedAt(Instant.now());
        observedUsers.save(snapshot);
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
