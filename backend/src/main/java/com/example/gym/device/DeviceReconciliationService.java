package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceUserSnapshotRow;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.ReconciliationConflict;
import com.example.gym.device.domain.ReconciliationConflictStatus;
import com.example.gym.device.domain.ReconciliationConflictType;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.device.repo.DeviceUserSnapshotRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.ReconciliationConflictRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Compares a device's reported user list to MySQL desired authorization. Unmapped device users are
 * imported (or linked by member code) and fanned out to other devices; they only become conflicts
 * while a removal is in flight. Missing / mismatched users get repair commands.
 */
@Service
public class DeviceReconciliationService {

    private final MemberDeviceMappingRepository mappingRepository;
    private final ReconciliationConflictRepository conflictRepository;
    private final DeviceUserSnapshotRepository snapshotRepository;
    private final DeviceSyncService deviceSyncService;
    private final DeviceAuthorizationService authorizationService;
    private final MemberRepository memberRepository;
    private final AuditService auditService;
    private final DeviceUserChangeService deviceUserChangeService;

    public DeviceReconciliationService(MemberDeviceMappingRepository mappingRepository,
                                       ReconciliationConflictRepository conflictRepository,
                                       DeviceUserSnapshotRepository snapshotRepository,
                                       DeviceSyncService deviceSyncService,
                                       DeviceAuthorizationService authorizationService,
                                       MemberRepository memberRepository,
                                       AuditService auditService,
                                       DeviceUserChangeService deviceUserChangeService) {
        this.deviceUserChangeService = deviceUserChangeService;
        this.mappingRepository = mappingRepository;
        this.conflictRepository = conflictRepository;
        this.snapshotRepository = snapshotRepository;
        this.deviceSyncService = deviceSyncService;
        this.authorizationService = authorizationService;
        this.memberRepository = memberRepository;
        this.auditService = auditService;
    }

    @Transactional
    public void applyDeviceUserSnapshot(Device device, JsonNode payload) {
        Set<String> deviceUserIds = new HashSet<>();
        Map<String, Boolean> frozenByUser = new LinkedHashMap<>();
        Map<String, ParsedUser> parsedUsers = new LinkedHashMap<>();

        if (payload != null && payload.has("deviceUsers") && payload.get("deviceUsers").isArray()) {
            for (JsonNode u : payload.get("deviceUsers")) {
                String id = text(u, "deviceUserId");
                if (id == null) {
                    continue;
                }
                deviceUserIds.add(id);
                boolean frozen = u.has("frozen") && u.get("frozen").asBoolean();
                frozenByUser.put(id, frozen);
                parsedUsers.put(id, new ParsedUser(
                        id,
                        text(u, "name"),
                        frozen,
                        parseDate(text(u, "validFrom")),
                        parseDate(text(u, "validTo"))));
            }
        } else if (payload != null && payload.has("deviceUserIds") && payload.get("deviceUserIds").isArray()) {
            for (JsonNode n : payload.get("deviceUserIds")) {
                if (n != null && !n.isNull()) {
                    String id = n.asString();
                    if (id != null && !id.isBlank()) {
                        String trimmed = id.trim();
                        deviceUserIds.add(trimmed);
                        parsedUsers.put(trimmed, new ParsedUser(trimmed, null, false, null, null));
                    }
                }
            }
        }

        replaceSnapshotCache(device, parsedUsers);

        List<MemberDeviceMapping> mappings = mappingRepository.findByDeviceId(device.getId());
        Set<String> mappedIds = new HashSet<>();
        for (MemberDeviceMapping mapping : mappings) {
            mappedIds.add(mapping.getDeviceUserId());
            boolean onDevice = deviceUserIds.contains(mapping.getDeviceUserId());
            Boolean frozen = frozenByUser.get(mapping.getDeviceUserId());

            if (!onDevice) {
                openConflict(device, mapping.getDeviceUserId(),
                        ReconciliationConflictType.MISSING_ON_DEVICE,
                        "Mapped user missing on device; enqueueing CREATE_USER repair");
                enqueueCreateRepair(device, mapping);
                continue;
            }

            Member member = memberRepository.findById(mapping.getMemberId()).orElse(null);
            Optional<DeviceAuthorizationService.AccessWindow> window = member == null
                    ? Optional.empty() : authorizationService.window(member);
            boolean desiredEnabled = window.map(DeviceAuthorizationService.AccessWindow::enabled).orElse(false);
            ParsedUser reported = parsedUsers.get(mapping.getDeviceUserId());

            if (frozen != null && frozen == desiredEnabled) {
                // frozen==true means disabled; desiredEnabled true means should not be frozen
                openConflict(device, mapping.getDeviceUserId(),
                        ReconciliationConflictType.AUTH_MISMATCH,
                        "Device freeze=" + frozen + " desiredEnabled=" + desiredEnabled);
                enqueueAuthRepair(device, mapping, member, desiredEnabled);
            } else if (frozen == null && !desiredEnabled) {
                // no freeze info — still push DISABLE if membership says so
                enqueueAuthRepair(device, mapping, member, false);
            } else if (window.isPresent() && reported != null && reported.validFrom() != null
                    && reported.validTo() != null
                    && (!reported.validFrom().equals(window.get().validFrom())
                        || !reported.validTo().equals(window.get().validTo()))) {
                // Right status, old dates (e.g. the switch to the next membership did not reach it yet).
                enqueueAuthRepair(device, mapping, member, desiredEnabled);
            }
        }

        for (String deviceUserId : deviceUserIds) {
            if (mappedIds.contains(deviceUserId)) {
                continue;
            }
            ParsedUser u = parsedUsers.get(deviceUserId);
            boolean imported = deviceUserChangeService.importFromReconcile(
                    device, deviceUserId, u.name(), u.frozen(), u.validFrom(), u.validTo());
            if (!imported) {
                openConflict(device, deviceUserId,
                        ReconciliationConflictType.EXTRA_DEVICE_USER,
                        "Device user has no member_device_mapping; import deferred");
            }
        }
    }

    private void replaceSnapshotCache(Device device, Map<String, ParsedUser> users) {
        snapshotRepository.deleteByDeviceId(device.getId());
        snapshotRepository.flush();
        for (ParsedUser u : users.values()) {
            snapshotRepository.save(new DeviceUserSnapshotRow(
                    device.getTenantId(),
                    device.getId(),
                    u.deviceUserId(),
                    u.name(),
                    u.frozen(),
                    u.validFrom(),
                    u.validTo()));
        }
    }

    private void enqueueCreateRepair(Device device, MemberDeviceMapping mapping) {
        Member member = memberRepository.findById(mapping.getMemberId()).orElse(null);
        Map<String, Object> createPayload = new LinkedHashMap<>();
        createPayload.put("deviceUserId", mapping.getDeviceUserId());
        createPayload.put("name", member == null ? mapping.getDeviceUserId() : member.getFullName());
        if (member != null) {
            createPayload.put("memberCode", member.getMemberCode());
        }
        deviceSyncService.enqueue(device.getTenantId(), device.getId(), mapping.getMemberId(), null,
                SyncCommandType.CREATE_USER, createPayload);
        if (member != null) {
            authorizationService.enqueueFor(member, device.getId(), mapping.getDeviceUserId());
        }
        auditService.record(AuditActions.RECONCILIATION_REPAIR_ENQUEUED, AuditActions.RESULT_SUCCESS,
                "Device", device.getPublicId(),
                Map.of("deviceUserId", mapping.getDeviceUserId(), "type", "CREATE_USER"));
    }

    private void enqueueAuthRepair(Device device, MemberDeviceMapping mapping, Member member,
                                   boolean desiredEnabled) {
        if (member != null) {
            authorizationService.enqueueFor(member, device.getId(), mapping.getDeviceUserId());
        } else {
            deviceSyncService.enqueue(device.getTenantId(), device.getId(), mapping.getMemberId(), null,
                    SyncCommandType.DISABLE_USER, DeviceAuthorizationService.disablePayload(mapping.getDeviceUserId()));
        }
        auditService.record(AuditActions.RECONCILIATION_REPAIR_ENQUEUED, AuditActions.RESULT_SUCCESS,
                "Device", device.getPublicId(),
                Map.of("deviceUserId", mapping.getDeviceUserId(),
                        "type", desiredEnabled ? "UPDATE_VALIDITY" : "DISABLE_USER"));
    }

    private void openConflict(Device device, String deviceUserId, ReconciliationConflictType type,
                              String details) {
        Optional<ReconciliationConflict> existing = conflictRepository
                .findFirstByDeviceIdAndDeviceUserIdAndConflictTypeAndStatus(
                        device.getId(), deviceUserId, type, ReconciliationConflictStatus.OPEN);
        if (existing.isPresent()) {
            return;
        }
        ReconciliationConflict conflict = conflictRepository.save(
                new ReconciliationConflict(device.getTenantId(), device.getId(), deviceUserId, type, details));
        auditService.record(AuditActions.RECONCILIATION_CONFLICT_OPENED, AuditActions.RESULT_SUCCESS,
                "ReconciliationConflict", conflict.getPublicId(),
                Map.of("type", type.name(), "deviceUserId", deviceUserId));
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            if (iso.length() >= 10 && iso.charAt(4) == '-') {
                // date or datetime
                if (iso.contains("T")) {
                    return OffsetDateTime.parse(iso).toLocalDate();
                }
                return LocalDate.parse(iso.substring(0, 10));
            }
        } catch (Exception ignored) {
            try {
                return Instant.parse(iso).atZone(ZoneOffset.UTC).toLocalDate();
            } catch (Exception ignored2) {
                return null;
            }
        }
        return null;
    }

    private record ParsedUser(
            String deviceUserId,
            String name,
            boolean frozen,
            LocalDate validFrom,
            LocalDate validTo) {
    }
}
