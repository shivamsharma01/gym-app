package com.example.gym.device;

import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Says when a person a reader shows may already be a member, and when a member may already be one
 * of the people a reader shows that staff have not decided on. Two people are a possible match when
 * the reader photo has the same SHA-256 as the member photo, or when the names are equal ignoring
 * case and spacing. A match is never linked; it only stops an automatic create or seed, so staff
 * decide. A reader that re-encodes the photo defeats the photo comparison; the name still applies.
 */
@Component
public class MemberMatcher {

    private final MemberRepository members;
    private final MemberFaceRepository faces;
    private final PendingEnrollmentRepository enrollments;
    private final DeviceObservedUserRepository observedUsers;

    public MemberMatcher(MemberRepository members,
                         MemberFaceRepository faces,
                         PendingEnrollmentRepository enrollments,
                         DeviceObservedUserRepository observedUsers) {
        this.members = members;
        this.faces = faces;
        this.enrollments = enrollments;
        this.observedUsers = observedUsers;
    }

    /** The member, active or not, a reader person may already be; lowest id first. */
    public Optional<Member> existing(Long tenantId, DeviceObservedUser observed) {
        return candidates(tenantId, observed).stream().findFirst();
    }

    /** Every member, active or not, a reader person may already be; lowest id first. */
    public List<Member> candidates(Long tenantId, DeviceObservedUser observed) {
        if (observed == null) {
            return List.of();
        }
        Map<Long, Member> found = new TreeMap<>();
        if (observed.getFaceSha256() != null && !observed.getFaceSha256().isBlank()) {
            for (Member member : members.findByTenantIdAndFaceSha256(
                    tenantId, observed.getFaceSha256().toLowerCase(Locale.ROOT))) {
                found.put(member.getId(), member);
            }
        }
        String name = normalized(ReaderReviewService.shown(observed.getReaderName(), observed.getReaderNameEx()));
        if (!name.isEmpty()) {
            for (Member member : members.findByTenantId(tenantId)) {
                if (name.equals(normalized(member.getFullName()))) {
                    found.put(member.getId(), member);
                }
            }
        }
        return List.copyOf(found.values());
    }

    /** Whether a scan has shown this reader holding someone at the id. */
    public boolean seenAt(Device reader, String deviceUserId) {
        return observedUsers.findByDeviceIdAndDeviceUserId(reader.getId(), deviceUserId).isPresent();
    }

    /**
     * What one reader shows that staff have not decided on yet, read once for many members. The
     * caller holds the reader lock; the read is a locking read, so a person observed after the
     * caller's snapshot still holds a seed back.
     */
    public Undecided undecided(Device reader) {
        List<DeviceObservedUser> people = observedUsers.lockUndecided(reader.getId());
        if (people.isEmpty()) {
            return Undecided.NONE;
        }
        Set<String> fromReaders = new HashSet<>();
        for (PendingEnrollment created : enrollments.findByTenantIdAndDecision(reader.getTenantId(), ReviewDecisions.CREATE)) {
            fromReaders.add(created.getChosenState());
        }
        Set<String> names = new HashSet<>();
        Set<String> photos = new HashSet<>();
        for (DeviceObservedUser person : people) {
            String name = normalized(ReaderReviewService.shown(person.getReaderName(), person.getReaderNameEx()));
            if (!name.isEmpty()) {
                names.add(name);
            }
            if (person.getFaceSha256() != null && !person.getFaceSha256().isBlank()) {
                photos.add(person.getFaceSha256().toLowerCase(Locale.ROOT));
            }
        }
        return new Undecided(fromReaders, names, photos, faces);
    }

    static String normalized(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * People one reader shows that staff have not decided on. A member is held back from that
     * reader when created from any reader's person, or when they may be one of these people.
     */
    public static final class Undecided {

        static final Undecided NONE = new Undecided(Set.of(), Set.of(), Set.of(), null);

        private final Set<String> fromReaders;
        private final Set<String> names;
        private final Set<String> photos;
        private final MemberFaceRepository faces;

        private Undecided(Set<String> fromReaders, Set<String> names, Set<String> photos, MemberFaceRepository faces) {
            this.fromReaders = fromReaders;
            this.names = names;
            this.photos = photos;
            this.faces = faces;
        }

        public boolean holds(Member member) {
            if (faces == null) {
                return false;
            }
            if (fromReaders.contains(member.getPublicId()) || names.contains(normalized(member.getFullName()))) {
                return true;
            }
            return !photos.isEmpty() && faces.findByMemberId(member.getId())
                    .map(MemberFace::getSha256)
                    .map(sha -> photos.contains(sha.toLowerCase(Locale.ROOT)))
                    .orElse(false);
        }
    }
}
