package com.example.gym.device;

import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Undecided people on one reader who are, beyond reasonable doubt, a member another reader
 * already holds. A person qualifies only when every one of these holds:
 * <ul>
 *   <li>nobody else undecided on this reader has the same name;</li>
 *   <li>exactly one member has that name, and no other member has the reader photo;</li>
 *   <li>that member is not on this reader yet, has a photo, and is on at least one other reader;</li>
 *   <li>every other reader holding the member shows the same name, dates and enabled state as this one.</li>
 * </ul>
 * Anyone else stays on the Review page for a one-by-one decision. Nothing is linked here.
 */
@Component
public class ReaderLinkMatches {

    private final PendingEnrollmentRepository enrollments;
    private final DeviceObservedUserRepository observedUsers;
    private final MemberDeviceMappingRepository mappings;
    private final MemberRepository members;
    private final MemberFaceRepository faces;
    private final DeviceRepository devices;

    public ReaderLinkMatches(PendingEnrollmentRepository enrollments,
                             DeviceObservedUserRepository observedUsers,
                             MemberDeviceMappingRepository mappings,
                             MemberRepository members,
                             MemberFaceRepository faces,
                             DeviceRepository devices) {
        this.enrollments = enrollments;
        this.observedUsers = observedUsers;
        this.mappings = mappings;
        this.members = members;
        this.faces = faces;
        this.devices = devices;
    }

    public record Match(String enrollmentId, String deviceUserId, String readerName, String validFrom,
                        String validTo, int userStatus, String memberId, String memberCode,
                        String memberName, String otherReaderName) {
    }

    private record Open(PendingEnrollment enrollment, DeviceObservedUser observed) {
    }

    public List<Match> find(Device reader) {
        List<Open> open = new ArrayList<>();
        Map<String, Integer> namesHere = new HashMap<>();
        for (PendingEnrollment enrollment : enrollments.findByDeviceIdAndResolvedFalse(reader.getId())) {
            if (enrollment.getDecision() != null) {
                continue;
            }
            observedUsers.findByDeviceIdAndDeviceUserId(reader.getId(), enrollment.getDeviceUserId())
                    .ifPresent(observed -> {
                        open.add(new Open(enrollment, observed));
                        namesHere.merge(name(observed), 1, Integer::sum);
                    });
        }
        if (open.isEmpty()) {
            return List.of();
        }
        Map<String, List<Member>> byName = new HashMap<>();
        for (Member member : members.findByTenantId(reader.getTenantId())) {
            byName.computeIfAbsent(MemberMatcher.normalized(member.getFullName()), ignored -> new ArrayList<>())
                    .add(member);
        }
        List<Match> found = new ArrayList<>();
        for (Open person : open) {
            String name = name(person.observed());
            List<Member> named = byName.getOrDefault(name, List.of());
            if (!name.isEmpty() && namesHere.get(name) == 1 && named.size() == 1) {
                match(reader, person.enrollment(), person.observed(), named.get(0)).ifPresent(found::add);
            }
        }
        return found;
    }

    private Optional<Match> match(Device reader, PendingEnrollment enrollment, DeviceObservedUser observed,
                                  Member member) {
        if (photoBelongsToSomeoneElse(reader.getTenantId(), observed, member)
                || faces.findByMemberId(member.getId()).isEmpty()
                || mappings.findByDeviceIdAndDeviceUserId(reader.getId(), observed.getDeviceUserId()).isPresent()) {
            return Optional.empty();
        }
        Device other = null;
        for (MemberDeviceMapping mapping : mappings.findByMemberId(member.getId())) {
            if (reader.getId().equals(mapping.getDeviceId())) {
                return Optional.empty();
            }
            DeviceObservedUser there = observedUsers
                    .findByDeviceIdAndDeviceUserId(mapping.getDeviceId(), mapping.getDeviceUserId())
                    .orElse(null);
            if (there == null || !samePerson(observed, there)) {
                return Optional.empty();
            }
            if (other == null) {
                other = devices.findById(mapping.getDeviceId()).orElse(null);
            }
        }
        if (other == null) {
            return Optional.empty();
        }
        return Optional.of(new Match(enrollment.getPublicId(), observed.getDeviceUserId(),
                ReaderReviewService.shown(observed.getReaderName(), observed.getReaderNameEx()),
                observed.getValidFrom(), observed.getValidTo(), observed.getUserStatus(),
                member.getPublicId(), member.getMemberCode(), member.getFullName(), other.getName()));
    }

    private boolean photoBelongsToSomeoneElse(Long tenantId, DeviceObservedUser observed, Member member) {
        String sha = observed.getFaceSha256();
        if (sha == null || sha.isBlank()) {
            return false;
        }
        return members.findByTenantIdAndFaceSha256(tenantId, sha.toLowerCase(Locale.ROOT)).stream()
                .anyMatch(owner -> !owner.getId().equals(member.getId()));
    }

    private static boolean samePerson(DeviceObservedUser here, DeviceObservedUser there) {
        return name(here).equals(name(there))
                && Objects.equals(here.getValidFrom(), there.getValidFrom())
                && Objects.equals(here.getValidTo(), there.getValidTo())
                && here.getUserStatus() == there.getUserStatus();
    }

    private static String name(DeviceObservedUser observed) {
        return MemberMatcher.normalized(ReaderReviewService.shown(observed.getReaderName(), observed.getReaderNameEx()));
    }
}
