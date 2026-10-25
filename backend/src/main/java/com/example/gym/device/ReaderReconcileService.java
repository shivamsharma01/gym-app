package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts every active member with a face on every reader of the gym that has a gateway and no
 * mapping for that member, through the desired-state seed. An existing mapping, a staff removal
 * included, is never rewritten, and people the server does not know are never linked. Running it
 * again writes nothing.
 * <p>
 * Every trigger seeds through here: a trusted roster, the bootstrap report, staff Reconcile,
 * reactivation, and a first photo. While a reader shows people staff have not decided on, a member
 * created from any reader's person, or who may be one of those people, is held back from that
 * reader: a seed would add them a second time under a new id. Staff link them instead, and the next
 * trigger after the last decision seeds the rest.
 */
@Service
public class ReaderReconcileService {

    private final DeviceRepository devices;
    private final MemberRepository members;
    private final MemberDeviceMappingRepository mappings;
    private final DesiredProjectionService desired;
    private final AuditService audit;
    private final MemberMatcher matcher;

    public ReaderReconcileService(DeviceRepository devices,
                                  MemberRepository members,
                                  MemberDeviceMappingRepository mappings,
                                  DesiredProjectionService desired,
                                  AuditService audit,
                                  MemberMatcher matcher) {
        this.devices = devices;
        this.members = members;
        this.mappings = mappings;
        this.desired = desired;
        this.audit = audit;
        this.matcher = matcher;
    }

    public record Reconciled(String deviceId, int written, int held) {
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
        Reconciled done = seed(reader);
        audit.record(AuditActions.DEVICE_RECONCILE_REQUESTED, AuditActions.RESULT_SUCCESS,
                "Device", reader.getPublicId(), Map.of("written", done.written(), "held", done.held()));
        return done;
    }

    /** One reader, under its lock. Returns how many members were written. */
    @Transactional
    public int reconcile(Device reader) {
        return seed(reader).written();
    }

    /**
     * Seeds at most {@code limit} active members whose id is greater than {@code afterMemberId}.
     * Returns the last member id looked at, or null when the member list is exhausted. Callers
     * repeat until null so a large gym commits the reader lock between slices.
     */
    @Transactional
    public Long seedAfter(Device reader, Long afterMemberId, int limit) {
        if (reader == null || reader.getGatewayId() == null || limit <= 0) {
            return null;
        }
        desired.lockReader(reader);
        Set<Long> mapped = new HashSet<>(mappings.lockMemberIdsByDeviceId(reader.getId()));
        MemberMatcher.Undecided undecided = matcher.undecided(reader);
        int taken = 0;
        int written = 0;
        int held = 0;
        Long last = null;
        for (Member member : members.findByTenantIdAndStatus(reader.getTenantId(), MemberStatus.ACTIVE).stream()
                .sorted(Comparator.comparing(Member::getId))
                .toList()) {
            if (afterMemberId != null && member.getId() <= afterMemberId) {
                continue;
            }
            last = member.getId();
            taken++;
            if (!mapped.contains(member.getId())) {
                if (undecided.holds(member)) {
                    held++;
                } else if (desired.seedIfAbsent(member, reader) != null) {
                    written++;
                }
            }
            if (taken >= limit) {
                break;
            }
        }
        if (written > 0 || held > 0) {
            FlowLog.info("device", "reconciled reader={} written={} held={}", reader.getPublicId(), written, held);
        }
        return taken < limit ? null : last;
    }

    private Reconciled seed(Device reader) {
        if (reader == null || reader.getGatewayId() == null) {
            return new Reconciled(reader == null ? null : reader.getPublicId(), 0, 0);
        }
        desired.lockReader(reader);
        Set<Long> mapped = new HashSet<>(mappings.lockMemberIdsByDeviceId(reader.getId()));
        MemberMatcher.Undecided undecided = matcher.undecided(reader);
        int written = 0;
        int held = 0;
        for (Member member : members.findByTenantIdAndStatus(reader.getTenantId(), MemberStatus.ACTIVE)) {
            if (mapped.contains(member.getId())) {
                continue;
            }
            if (undecided.holds(member)) {
                held++;
            } else if (desired.seedIfAbsent(member, reader) != null) {
                written++;
            }
        }
        if (written > 0 || held > 0) {
            FlowLog.info("device", "reconciled reader={} written={} held={}", reader.getPublicId(), written, held);
        }
        return new Reconciled(reader.getPublicId(), written, held);
    }

    /**
     * One member on every reader of the gym that lacks them. Every reader's lock is taken first,
     * lowest device id first, so a publish that follows in the same transaction keeps that order.
     */
    @Transactional
    public void reconcile(Member member) {
        List<Device> readers = lockGatewayReaders(member.getTenantId());
        if (member.getStatus() != MemberStatus.ACTIVE) {
            return;
        }
        for (Device reader : readers) {
            if (matcher.undecided(reader).holds(member)) {
                FlowLog.info("device", "seed held reader={} member={}", reader.getPublicId(), member.getPublicId());
            } else {
                desired.seedIfAbsent(member, reader);
            }
        }
    }

    /**
     * A reader shows {@code person}, whom staff have not decided on. Every member they may already
     * be loses a seed on this reader the gateway was never sent, so the reader is not given a second
     * identity of the same person. Call with the reader locked; the lock keeps a pull out until the
     * caller commits.
     */
    @Transactional
    public void withdrawUnservedSeeds(Device reader, DeviceObservedUser person) {
        for (Member member : matcher.candidates(reader.getTenantId(), person)) {
            desired.withdrawUnservedSeed(member, reader, deviceUserId -> matcher.seenAt(reader, deviceUserId));
        }
    }

    /** Held until the caller's transaction ends. */
    @Transactional
    public void lockReader(Device reader) {
        desired.lockReader(reader);
    }

    /** Every reader of the gym, lowest device id first, until the caller's transaction ends. */
    @Transactional
    public void lockReaders(Long tenantId) {
        lockGatewayReaders(tenantId);
    }

    private List<Device> lockGatewayReaders(Long tenantId) {
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
