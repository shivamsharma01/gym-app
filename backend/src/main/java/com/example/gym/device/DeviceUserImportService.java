package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceUserSnapshotRow;
import com.example.gym.device.domain.EnrollmentStatus;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.ReconciliationConflict;
import com.example.gym.device.domain.ReconciliationConflictStatus;
import com.example.gym.device.domain.ReconciliationConflictType;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceUserSnapshotRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.ReconciliationConflictRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.DeviceSyncState;
import com.example.gym.tenant.TenantGuard;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Manual, idempotent import of cached device user lists into Members + Unknown memberships +
 * mappings. Reconcile now imports automatically; this remains as a fallback. Import records the
 * users a reader already holds and does not enqueue a write to any reader.
 */
@Service
public class DeviceUserImportService {

    private final DeviceRepository deviceRepository;
    private final DeviceUserSnapshotRepository snapshotRepository;
    private final MemberDeviceMappingRepository mappingRepository;
    private final MemberRepository memberRepository;
    private final ReconciliationConflictRepository conflictRepository;
    private final DeviceMemberImporter importer;
    private final DeviceService deviceService;
    private final AuditService auditService;

    public DeviceUserImportService(DeviceRepository deviceRepository,
                                   DeviceUserSnapshotRepository snapshotRepository,
                                   MemberDeviceMappingRepository mappingRepository,
                                   MemberRepository memberRepository,
                                   ReconciliationConflictRepository conflictRepository,
                                   DeviceMemberImporter importer,
                                   DeviceService deviceService,
                                   AuditService auditService) {
        this.deviceRepository = deviceRepository;
        this.snapshotRepository = snapshotRepository;
        this.mappingRepository = mappingRepository;
        this.memberRepository = memberRepository;
        this.conflictRepository = conflictRepository;
        this.importer = importer;
        this.deviceService = deviceService;
        this.auditService = auditService;
    }

    /**
     * Import from the last cached user lists for all devices in the tenant (including siblings).
     * Triggers a reconcile on the requested device so the next Sync/reconcile result refreshes
     * the cache; import itself uses currently stored snapshots.
     */
    @Transactional
    public ImportResult importUsers(String devicePublicId, Long tenantId) {
        Device device = deviceRepository.findByPublicId(devicePublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Device"));
        TenantGuard.check(device.getTenantId(), tenantId, "Device");

        deviceService.reconcile(devicePublicId, tenantId);

        List<Device> devices = deviceRepository.findByTenantId(tenantId);
        List<DeviceUserSnapshotRow> allRows = snapshotRepository.findByTenantId(tenantId);
        if (allRows.isEmpty()) {
            throw CommonExceptions.badRequest(
                    "No device user list cached yet. Run Sync Now / Reconcile first, wait for the "
                            + "gateway result, then import again.");
        }

        Map<String, MergedDeviceUser> merged = mergeByDeviceUserId(allRows);
        importer.ensureUnknownPlan(tenantId);

        int created = 0;
        int mapped = 0;
        int skipped = 0;
        int inactiveFrozen = 0;
        int inferredEndDates = 0;

        for (MergedDeviceUser user : merged.values()) {
            Optional<Member> known = findAnyMapping(devices, user.deviceUserId())
                    .flatMap(m -> memberRepository.findById(m.getMemberId()))
                    .or(() -> importer.findBySerial(tenantId, user.deviceUserId()));
            Member member;
            if (known.isPresent()) {
                member = known.get();
            } else {
                member = importer.createMember(tenantId, user.deviceUserId(), user.name(), user.frozenAnywhere());
                if (member.getStatus() == MemberStatus.INACTIVE) {
                    inactiveFrozen++;
                }
                if (importer.ensureUnknownMembership(member, user.validFrom(), user.validTo())) {
                    inferredEndDates++;
                }
                created++;
            }
            int added = ensureMappings(member, user, devices);
            mapped += added;
            if (known.isPresent() && added == 0) {
                skipped++;
            }
            closeExtraConflicts(user);
        }

        ImportResult result = new ImportResult(created, mapped, skipped, inactiveFrozen, inferredEndDates,
                merged.size());
        auditService.record(AuditActions.DEVICE_USERS_IMPORTED, AuditActions.RESULT_SUCCESS,
                "Device", device.getPublicId(),
                Map.of("created", created, "mapped", mapped, "skipped", skipped,
                        "inactiveFrozen", inactiveFrozen, "inferredEndDates", inferredEndDates,
                        "deviceUsers", merged.size()));
        return result;
    }

    private Map<String, MergedDeviceUser> mergeByDeviceUserId(List<DeviceUserSnapshotRow> rows) {
        Map<String, MergedDeviceUser> map = new LinkedHashMap<>();
        for (DeviceUserSnapshotRow row : rows) {
            MergedDeviceUser existing = map.get(row.getDeviceUserId());
            map.put(row.getDeviceUserId(), existing == null ? MergedDeviceUser.from(row) : existing.merge(row));
        }
        return map;
    }

    private Optional<MemberDeviceMapping> findAnyMapping(List<Device> devices, String deviceUserId) {
        for (Device d : devices) {
            Optional<MemberDeviceMapping> m = mappingRepository.findByDeviceIdAndDeviceUserId(d.getId(), deviceUserId);
            if (m.isPresent()) {
                return m;
            }
        }
        return Optional.empty();
    }

    private int ensureMappings(Member member, MergedDeviceUser user, List<Device> devices) {
        int added = 0;
        for (Device d : devices) {
            if (!user.deviceIds().contains(d.getId())) {
                continue;
            }
            if (mappingRepository.existsByDeviceIdAndDeviceUserId(d.getId(), user.deviceUserId())) {
                continue;
            }
            if (mappingRepository.existsByDeviceIdAndMemberId(d.getId(), member.getId())) {
                continue;
            }
            MemberDeviceMapping mapping = new MemberDeviceMapping(
                    member.getTenantId(), member.getId(), d.getId(), user.deviceUserId());
            mapping.setEnrollmentStatus(EnrollmentStatus.ENROLLED);
            mapping.setSyncState(DeviceSyncState.SYNCED);
            mapping.setEnrolledAt(Instant.now());
            mappingRepository.save(mapping);
            added++;
        }
        return added;
    }

    private void closeExtraConflicts(MergedDeviceUser user) {
        for (Long deviceId : user.deviceIds()) {
            List<ReconciliationConflict> open = conflictRepository.findByDeviceIdAndStatusAndDeviceUserId(
                    deviceId, ReconciliationConflictStatus.OPEN, user.deviceUserId());
            for (ReconciliationConflict c : open) {
                if (c.getConflictType() == ReconciliationConflictType.EXTRA_DEVICE_USER) {
                    c.resolve();
                    conflictRepository.save(c);
                }
            }
        }
    }

    private record MergedDeviceUser(
            String deviceUserId,
            String name,
            boolean frozenAnywhere,
            LocalDate validFrom,
            LocalDate validTo,
            Set<Long> deviceIds) {

        static MergedDeviceUser from(DeviceUserSnapshotRow row) {
            Set<Long> ids = new HashSet<>();
            ids.add(row.getDeviceId());
            return new MergedDeviceUser(row.getDeviceUserId(), row.getName(), row.isFrozen(),
                    row.getValidFrom(), row.getValidTo(), ids);
        }

        MergedDeviceUser merge(DeviceUserSnapshotRow row) {
            Set<Long> ids = new HashSet<>(deviceIds);
            ids.add(row.getDeviceId());
            String betterName = StringUtils.hasText(name) ? name : row.getName();
            LocalDate from = validFrom;
            LocalDate to = validTo;
            if (row.getValidFrom() != null && (from == null || row.getValidFrom().isBefore(from))) {
                from = row.getValidFrom();
            }
            if (row.getValidTo() != null && (to == null || row.getValidTo().isAfter(to))) {
                to = row.getValidTo();
            }
            return new MergedDeviceUser(deviceUserId, betterName, frozenAnywhere || row.isFrozen(), from, to, ids);
        }
    }

    public record ImportResult(
            int created,
            int mapped,
            int skipped,
            int inactiveFrozen,
            int inferredEndDates,
            int deviceUsersSeen) {
    }
}
