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
import com.example.gym.member.MemberCreationSource;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.DeviceSyncState;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipPaymentStatus;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import com.example.gym.plan.MembershipPlan;
import com.example.gym.plan.MembershipPlanRepository;
import com.example.gym.tenant.TenantGuard;
import java.math.BigDecimal;
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
 * Idempotent import of device-side users into Members + Unknown memberships + mappings.
 * Does not enqueue face-replacing UPSERT/CREATE or ENABLE for frozen users.
 */
@Service
public class DeviceUserImportService {

    public static final String UNKNOWN_PLAN_NAME = "Unknown";

    private final DeviceRepository deviceRepository;
    private final DeviceUserSnapshotRepository snapshotRepository;
    private final MemberDeviceMappingRepository mappingRepository;
    private final MemberRepository memberRepository;
    private final MembershipRepository membershipRepository;
    private final MembershipPlanRepository planRepository;
    private final ReconciliationConflictRepository conflictRepository;
    private final DeviceService deviceService;
    private final AuditService auditService;

    public DeviceUserImportService(DeviceRepository deviceRepository,
                                   DeviceUserSnapshotRepository snapshotRepository,
                                   MemberDeviceMappingRepository mappingRepository,
                                   MemberRepository memberRepository,
                                   MembershipRepository membershipRepository,
                                   MembershipPlanRepository planRepository,
                                   ReconciliationConflictRepository conflictRepository,
                                   DeviceService deviceService,
                                   AuditService auditService) {
        this.deviceRepository = deviceRepository;
        this.snapshotRepository = snapshotRepository;
        this.mappingRepository = mappingRepository;
        this.memberRepository = memberRepository;
        this.membershipRepository = membershipRepository;
        this.planRepository = planRepository;
        this.conflictRepository = conflictRepository;
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

        // Refresh path for next time; import uses cache already present across the gym.
        deviceService.reconcile(devicePublicId, tenantId);

        List<Device> devices = deviceRepository.findByTenantId(tenantId);
        List<DeviceUserSnapshotRow> allRows = snapshotRepository.findByTenantId(tenantId);
        if (allRows.isEmpty()) {
            throw CommonExceptions.badRequest(
                    "No device user list cached yet. Run Sync Now / Reconcile first, wait for the "
                            + "gateway result, then import again.");
        }

        Map<String, MergedDeviceUser> merged = mergeByDeviceUserId(allRows);
        MembershipPlan unknownPlan = ensureUnknownPlan(tenantId);

        int created = 0;
        int mapped = 0;
        int skipped = 0;
        int inactiveFrozen = 0;
        int inferredEndDates = 0;

        for (MergedDeviceUser user : merged.values()) {
            Optional<MemberDeviceMapping> existingMapping = findAnyMapping(devices, user.deviceUserId());
            if (existingMapping.isPresent()) {
                Member member = memberRepository.findById(existingMapping.get().getMemberId()).orElse(null);
                if (member != null) {
                    int added = ensureMappings(member, user, devices);
                    mapped += added;
                    if (added == 0) {
                        skipped++;
                    }
                    closeExtraConflicts(user);
                } else {
                    skipped++;
                }
                continue;
            }

            Optional<Member> byCode = memberRepository.findByTenantIdAndMemberCode(tenantId, truncateCode(user.deviceUserId()));
            if (byCode.isPresent()) {
                int added = ensureMappings(byCode.get(), user, devices);
                mapped += added;
                if (added == 0) {
                    skipped++;
                }
                closeExtraConflicts(user);
                continue;
            }

            Member member = createImportedMember(tenantId, user);
            if (member.getStatus() == MemberStatus.INACTIVE) {
                inactiveFrozen++;
            }
            boolean inferred = ensureUnknownMembership(member, unknownPlan, user);
            if (inferred) {
                inferredEndDates++;
            }
            mapped += ensureMappings(member, user, devices);
            closeExtraConflicts(user);
            created++;
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
            if (existing == null) {
                map.put(row.getDeviceUserId(), MergedDeviceUser.from(row));
            } else {
                map.put(row.getDeviceUserId(), existing.merge(row));
            }
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

    private Member createImportedMember(Long tenantId, MergedDeviceUser user) {
        String code = truncateCode(user.deviceUserId());
        if (memberRepository.existsByTenantIdAndMemberCode(tenantId, code)) {
            code = truncateCode("DEV-" + user.deviceUserId());
        }
        NameParts names = parseName(user.name(), user.deviceUserId());
        Member member = new Member(tenantId, code, names.firstName());
        member.setLastName(names.lastName());
        member.setCreationSource(MemberCreationSource.DEVICE_IMPORT);
        member.setStatus(user.frozenAnywhere() ? MemberStatus.INACTIVE : MemberStatus.ACTIVE);
        return memberRepository.save(member);
    }

    private boolean ensureUnknownMembership(Member member, MembershipPlan plan, MergedDeviceUser user) {
        LocalDate today = LocalDate.now();
        boolean hasCurrent = membershipRepository.findByMemberIdAndDeletedFalseOrderByStartDateDesc(member.getId())
                .stream()
                .filter(m -> m.getStatus() != MembershipStatus.CANCELLED)
                .anyMatch(m -> m.coversDate(today));
        if (hasCurrent) {
            return false;
        }

        LocalDate start = user.validFrom() != null ? user.validFrom() : today;
        boolean inferred = user.validTo() == null;
        LocalDate end = inferred ? today.plusYears(1) : user.validTo();
        if (end.isBefore(start)) {
            end = start.plusYears(1);
            inferred = true;
        }

        Membership membership = new Membership(
                member.getTenantId(),
                member.getId(),
                plan.getId(),
                plan.getName(),
                plan.getPrice(),
                plan.getCurrency(),
                start,
                end,
                MembershipStatus.ACTIVE);
        membership.setPaymentStatus(MembershipPaymentStatus.PAID);
        membership.setAmountPaid(BigDecimal.ZERO);
        membership.setEndDateInferred(inferred);
        membership.setDeviceSyncState(DeviceSyncState.SYNCED);
        membershipRepository.save(membership);
        // Intentionally no MembershipChangedEvent — do not push CREATE/UPSERT/ENABLE to device.
        return inferred;
    }

    private int ensureMappings(Member member, MergedDeviceUser user, List<Device> devices) {
        int added = 0;
        Set<Long> deviceIdsOnUser = user.deviceIds();
        for (Device d : devices) {
            if (!deviceIdsOnUser.contains(d.getId())) {
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

    private MembershipPlan ensureUnknownPlan(Long tenantId) {
        return planRepository.findFirstByTenantIdAndNameIgnoreCase(tenantId, UNKNOWN_PLAN_NAME)
                .orElseGet(() -> planRepository.save(new MembershipPlan(
                        tenantId,
                        UNKNOWN_PLAN_NAME,
                        "Placeholder plan for members imported from devices; replace with the correct plan later.",
                        BigDecimal.ZERO,
                        "INR",
                        365)));
    }

    private static String truncateCode(String raw) {
        String code = raw == null ? "UNKNOWN" : raw.trim();
        if (code.length() > 32) {
            return code.substring(0, 32);
        }
        return code;
    }

    private static NameParts parseName(String deviceName, String fallbackId) {
        String raw = StringUtils.hasText(deviceName) ? deviceName.trim() : fallbackId;
        int space = raw.indexOf(' ');
        if (space < 0) {
            return new NameParts(raw, null);
        }
        return new NameParts(raw.substring(0, space), raw.substring(space + 1).trim());
    }

    private record NameParts(String firstName, String lastName) {
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
            return new MergedDeviceUser(
                    row.getDeviceUserId(),
                    row.getName(),
                    row.isFrozen(),
                    row.getValidFrom(),
                    row.getValidTo(),
                    ids);
        }

        MergedDeviceUser merge(DeviceUserSnapshotRow row) {
            Set<Long> ids = new HashSet<>(deviceIds);
            ids.add(row.getDeviceId());
            String betterName = StringUtils.hasText(name) ? name : row.getName();
            LocalDate from = validFrom != null ? validFrom : row.getValidFrom();
            LocalDate to = validTo != null ? validTo : row.getValidTo();
            // Prefer later end / earlier start when both present
            if (row.getValidFrom() != null && (from == null || row.getValidFrom().isBefore(from))) {
                from = row.getValidFrom();
            }
            if (row.getValidTo() != null && (to == null || row.getValidTo().isAfter(to))) {
                to = row.getValidTo();
            }
            return new MergedDeviceUser(
                    deviceUserId,
                    betterName,
                    frozenAnywhere || row.isFrozen(),
                    from,
                    to,
                    ids);
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
