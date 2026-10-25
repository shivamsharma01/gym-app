package com.example.gym.device;

import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.member.DeviceAuthority;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * When another reader already shows the same person the server just learned, link that reader's own
 * id. Staff do not have to confirm a copy: the photo belongs to exactly one member, and the name,
 * dates, enabled state and terminal level agree. A difference stays on Review. A later edit then
 * reaches every reader that already had the person.
 */
@Service
public class SeenMemberClaim {

    private static final ZoneId READER_ZONE = ZoneId.of("Asia/Kolkata");

    private final PendingEnrollmentRepository enrollments;
    private final DeviceObservedUserRepository observedUsers;
    private final DeviceRepository devices;
    private final MemberRepository members;
    private final MemberFaceRepository faces;
    private final MemberDeviceMappingRepository mappings;
    private final ObjectProvider<DesiredProjectionService> desired;

    public SeenMemberClaim(PendingEnrollmentRepository enrollments,
                           DeviceObservedUserRepository observedUsers,
                           DeviceRepository devices,
                           MemberRepository members,
                           MemberFaceRepository faces,
                           MemberDeviceMappingRepository mappings,
                           ObjectProvider<DesiredProjectionService> desired) {
        this.enrollments = enrollments;
        this.observedUsers = observedUsers;
        this.devices = devices;
        this.members = members;
        this.faces = faces;
        this.mappings = mappings;
        this.desired = desired;
    }

    /** Link every quiet copy in the gym. Used when a reader is imported or read, and when Review opens. */
    @Transactional
    public int claimQuiet(Long tenantId) {
        if (tenantId == null) {
            return 0;
        }
        List<PendingEnrollment> open = enrollments.findByTenantIdAndResolvedFalse(tenantId).stream()
                .filter(enrollment -> enrollment.getDecision() == null)
                .toList();
        if (open.isEmpty()) {
            return 0;
        }
        Map<Long, List<PendingEnrollment>> byDevice = new HashMap<>();
        for (PendingEnrollment enrollment : open) {
            byDevice.computeIfAbsent(enrollment.getDeviceId(), id -> new ArrayList<>()).add(enrollment);
        }
        List<Long> deviceIds = new ArrayList<>(byDevice.keySet());
        deviceIds.sort(Comparator.naturalOrder());
        Map<String, List<Member>> ownersByHash = new HashMap<>();
        int linked = 0;
        for (Long deviceId : deviceIds) {
            Device device = devices.findById(deviceId).orElse(null);
            if (device == null) {
                continue;
            }
            linked += linkOpen(device, byDevice.get(deviceId), ownersByHash);
        }
        if (linked > 0) {
            FlowLog.info("device", "linked {} people who were already the same on another reader", linked);
        }
        return linked;
    }

    /** Open enrollments on this reader that staff have not decided, in id order. */
    @Transactional(readOnly = true)
    public List<Long> undecidedIds(Device device) {
        if (device == null) {
            return List.of();
        }
        return enrollments.findByDeviceIdAndResolvedFalse(device.getId()).stream()
                .filter(enrollment -> enrollment.getDecision() == null)
                .map(PendingEnrollment::getId)
                .sorted()
                .toList();
    }

    /** Links one slice of {@link #undecidedIds}. A slice commits on its own so the reader lock stays short. */
    @Transactional
    public void claimThese(Device device, List<Long> enrollmentIds) {
        if (device == null || enrollmentIds == null || enrollmentIds.isEmpty()) {
            return;
        }
        List<PendingEnrollment> open = new ArrayList<>();
        for (PendingEnrollment enrollment : enrollments.findAllById(enrollmentIds)) {
            if (device.getId().equals(enrollment.getDeviceId())
                    && enrollment.getDecision() == null && !enrollment.isResolved()) {
                open.add(enrollment);
            }
        }
        linkOpen(device, open, new HashMap<>());
    }

    /** Link quiet copies on this reader only. The caller may already hold its lock. */
    @Transactional
    public int claimQuiet(Device device) {
        if (device == null) {
            return 0;
        }
        List<PendingEnrollment> open = enrollments.findByDeviceIdAndResolvedFalse(device.getId()).stream()
                .filter(enrollment -> enrollment.getDecision() == null)
                .toList();
        int linked = linkOpen(device, open, new HashMap<>());
        if (linked > 0) {
            FlowLog.info("device", "linked {} people who were already the same on another reader", linked);
        }
        return linked;
    }

    private int linkOpen(Device device, List<PendingEnrollment> open, Map<String, List<Member>> ownersByHash) {
        if (open.isEmpty()) {
            return 0;
        }
        int linked = 0;
        {
            Map<String, DeviceObservedUser> byUser = new HashMap<>();
            Map<String, Integer> hashCount = new HashMap<>();
            for (DeviceObservedUser person : observedUsers.findByDeviceId(device.getId())) {
                byUser.put(person.getDeviceUserId(), person);
                String hash = hash(person.getFaceSha256());
                if (hash != null) {
                    hashCount.merge(hash, 1, Integer::sum);
                }
            }
            for (PendingEnrollment enrollment : open) {
                DeviceObservedUser person = byUser.get(enrollment.getDeviceUserId());
                String hash = person == null ? null : hash(person.getFaceSha256());
                if (hash == null || hashCount.getOrDefault(hash, 0) != 1) {
                    continue;
                }
                List<Member> owners = ownersByHash.computeIfAbsent(hash,
                        key -> members.findByTenantIdAndFaceSha256(device.getTenantId(), key));
                if (owners.size() == 1 && link(device, owners.get(0), person, true)) {
                    linked++;
                }
            }
        }
        return linked;
    }

    /**
     * Link this member onto readers that already show their photo, before an edit is published.
     * {@code requireSameFields} keeps a reader whose name, dates or access differ on Review.
     * A staff edit of the name passes false so the new name still reaches the other reader.
     */
    @Transactional
    public void claimMember(Member member, boolean requireSameFields) {
        if (member == null || member.getId() == null) {
            return;
        }
        MemberFace face = faces.findByMemberId(member.getId()).orElse(null);
        String hash = face == null ? null : hash(face.getSha256());
        if (hash == null) {
            return;
        }
        List<Member> owners = members.findByTenantIdAndFaceSha256(member.getTenantId(), hash);
        if (owners.size() != 1 || !member.getId().equals(owners.get(0).getId())) {
            return;
        }
        Map<Long, List<DeviceObservedUser>> byDevice = new HashMap<>();
        for (DeviceObservedUser person : observedUsers.findByTenantIdAndFaceSha256In(member.getTenantId(), List.of(hash))) {
            byDevice.computeIfAbsent(person.getDeviceId(), id -> new java.util.ArrayList<>()).add(person);
        }
        for (Map.Entry<Long, List<DeviceObservedUser>> entry : byDevice.entrySet()) {
            if (entry.getValue().size() != 1) {
                continue;
            }
            Device device = devices.findById(entry.getKey()).orElse(null);
            if (device != null) {
                link(device, member, entry.getValue().get(0), requireSameFields);
            }
        }
    }

    /** Same photo is not enough: the name, dates, enabled state and terminal level have to agree. */
    static boolean samePerson(Member member, DeviceObservedUser observed) {
        if (member == null || observed == null) {
            return false;
        }
        String shown = MemberMatcher.normalized(
                ReaderReviewService.shown(observed.getReaderName(), observed.getReaderNameEx()));
        if (shown.isEmpty() || !shown.equals(MemberMatcher.normalized(member.getFullName()))) {
            return false;
        }
        boolean readerEnabled = observed.getUserStatus() == DesiredProjectionService.USER_STATUS;
        if (readerEnabled != (member.getStatus() == MemberStatus.ACTIVE)) {
            return false;
        }
        boolean readerAdmin = DesiredProjectionService.isAdminAuthority(observed.getAuthority());
        if (readerAdmin != (member.getDeviceAuthority() == DeviceAuthority.ADMIN)) {
            return false;
        }
        LocalDate memberFrom = day(member.getAdoptedValidFrom());
        LocalDate memberTo = day(member.getAdoptedValidTo());
        if (memberFrom == null || memberTo == null) {
            memberFrom = member.getDeviceValidFrom();
            memberTo = member.getDeviceValidTo();
        }
        LocalDate readerFrom = day(observed.getValidFrom());
        LocalDate readerTo = day(observed.getValidTo());
        if (memberFrom == null && memberTo == null) {
            return true;
        }
        return memberFrom != null && memberTo != null && memberFrom.equals(readerFrom) && memberTo.equals(readerTo);
    }

    private boolean link(Device device, Member member, DeviceObservedUser observed, boolean requireSameFields) {
        if (device.getGatewayId() == null || observed == null) {
            return false;
        }
        if (requireSameFields && !samePerson(member, observed)) {
            return false;
        }
        if (mappings.existsByDeviceIdAndMemberId(device.getId(), member.getId())
                || mappings.existsByDeviceIdAndDeviceUserId(device.getId(), observed.getDeviceUserId())
                || mappings.findFirstByDeviceIdAndPendingDeviceUserId(device.getId(), observed.getDeviceUserId()).isPresent()) {
            return false;
        }
        MemberFace face = faces.findByMemberId(member.getId()).orElse(null);
        if (face == null || face.getSha256() == null || observed.getFaceSha256() == null
                || !face.getSha256().equalsIgnoreCase(observed.getFaceSha256())) {
            return false;
        }
        long revision = desired.getObject().recordAlreadyPresent(member, device, face, observed);
        enrollments.findByDeviceIdAndDeviceUserId(device.getId(), observed.getDeviceUserId()).ifPresent(enrollment -> {
            if (enrollment.getDecision() == null && !enrollment.isResolved()) {
                enrollment.decide(ReviewDecisions.LINK, "same-person", "PENDING", member.getPublicId(), revision);
                enrollment.resolve();
                enrollments.save(enrollment);
            }
        });
        FlowLog.info("device", "linked reader={} user={} member={}: already the same person",
                device.getPublicId(), observed.getDeviceUserId(), member.getPublicId());
        return true;
    }

    private static String hash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static LocalDate day(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.length() == 10) {
                return LocalDate.parse(text);
            }
            return OffsetDateTime.parse(text).atZoneSameInstant(READER_ZONE).toLocalDate();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
