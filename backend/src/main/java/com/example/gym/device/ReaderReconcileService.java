package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import com.example.gym.tenant.TenantGuard;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts every active member with a face on every reader of the gym that has a gateway and no
 * mapping for that member, through the desired-state seed. An existing mapping, a staff removal
 * included, is never rewritten, and people the server does not know are never linked. Running it
 * again writes nothing.
 */
@Service
public class ReaderReconcileService {

    private final DeviceRepository devices;
    private final MemberRepository members;
    private final MemberDeviceMappingRepository mappings;
    private final DesiredProjectionService desired;
    private final AuditService audit;

    public ReaderReconcileService(DeviceRepository devices,
                                  MemberRepository members,
                                  MemberDeviceMappingRepository mappings,
                                  DesiredProjectionService desired,
                                  AuditService audit) {
        this.devices = devices;
        this.members = members;
        this.mappings = mappings;
        this.desired = desired;
        this.audit = audit;
    }

    public record Reconciled(String deviceId, int written) {
    }

    /** Staff ask one reader to receive the active members it does not have yet. */
    @Transactional
    public Reconciled reconcile(String devicePublicId, Long tenantId) {
        Device reader = devices.findByPublicId(devicePublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Device"));
        TenantGuard.check(reader.getTenantId(), tenantId, "Device");
        if (reader.getGatewayId() == null) {
            throw CommonExceptions.badRequest("Reader has no gateway");
        }
        int written = reconcile(reader);
        audit.record(AuditActions.DEVICE_RECONCILE_REQUESTED, AuditActions.RESULT_SUCCESS,
                "Device", reader.getPublicId(), Map.of("written", written));
        return new Reconciled(reader.getPublicId(), written);
    }

    /** One reader, under its lock. Returns how many members were written. */
    @Transactional
    public int reconcile(Device reader) {
        return reconcile(reader, member -> true);
    }

    /** One reader, under its lock, writing only the members {@code eligible} accepts. */
    @Transactional
    public int reconcile(Device reader, Predicate<Member> eligible) {
        if (reader == null || reader.getGatewayId() == null) {
            return 0;
        }
        desired.lockReader(reader);
        Set<Long> mapped = new HashSet<>(mappings.lockMemberIdsByDeviceId(reader.getId()));
        int written = 0;
        for (Member member : members.findByTenantIdAndStatus(reader.getTenantId(), MemberStatus.ACTIVE)) {
            if (!mapped.contains(member.getId()) && eligible.test(member)
                    && desired.seedIfAbsent(member, reader) != null) {
                written++;
            }
        }
        if (written > 0) {
            FlowLog.info("device", "reconciled reader={} written={}", reader.getPublicId(), written);
        }
        return written;
    }

    /**
     * One member on every reader of the gym that lacks them. Every reader's lock is taken first,
     * lowest device id first, so a publish that follows in the same transaction keeps that order.
     */
    @Transactional
    public void reconcile(Member member) {
        List<Device> readers = lockReaders(member.getTenantId());
        if (member.getStatus() != MemberStatus.ACTIVE) {
            return;
        }
        for (Device reader : readers) {
            desired.seedIfAbsent(member, reader);
        }
    }

    /** Held until the caller's transaction ends. */
    @Transactional
    public void lockReader(Device reader) {
        desired.lockReader(reader);
    }

    private List<Device> lockReaders(Long tenantId) {
        List<Device> readers = devices.findByTenantId(tenantId).stream()
                .filter(device -> device.getGatewayId() != null)
                .sorted(Comparator.comparing(Device::getId))
                .toList();
        for (Device reader : readers) {
            desired.lockReader(reader);
        }
        return readers;
    }
}
