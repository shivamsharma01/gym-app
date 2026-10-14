package com.example.gym.device;

import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.dto.BootstrapReport;
import com.example.gym.device.dto.BootstrapReport.Row;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceReviewItemRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Classifies a trusted roster. A matching mapping stays on the desired-state writer. Everyone else
 * stays a review or enrollment row. Device user ids are not rewritten, and nobody is auto-linked.
 * Active members a reader does not have yet are seeded onto it from the server. A member created
 * from a reader's person waits until staff have decided on every person this reader showed.
 */
@Service
public class BootstrapReportService {

    static final String MATCHING = "MATCHING";
    static final String DIFFERENT = "DIFFERENT";
    static final String UNLINKED = "UNLINKED";
    static final String COLLISION = "COLLISION";
    static final String SEEDED = "SEEDED";

    private final DeviceRepository devices;
    private final MemberRepository members;
    private final DeviceObservedUserRepository observedUsers;
    private final MemberDeviceMappingRepository mappings;
    private final DeviceReviewItemRepository reviews;
    private final PendingEnrollmentRepository enrollments;
    private final ReaderReconcileService reconcile;

    public BootstrapReportService(DeviceRepository devices,
                                  MemberRepository members,
                                  DeviceObservedUserRepository observedUsers,
                                  MemberDeviceMappingRepository mappings,
                                  DeviceReviewItemRepository reviews,
                                  PendingEnrollmentRepository enrollments,
                                  ReaderReconcileService reconcile) {
        this.devices = devices;
        this.members = members;
        this.observedUsers = observedUsers;
        this.mappings = mappings;
        this.reviews = reviews;
        this.enrollments = enrollments;
        this.reconcile = reconcile;
    }

    @Transactional
    public BootstrapReport build(Long tenantId) {
        String runId = UUID.randomUUID().toString();
        List<Device> readers = devices.findByTenantId(tenantId).stream()
                .sorted(Comparator.comparing(Device::getId))
                .toList();
        List<Member> people = members.findByTenantIdAndStatus(tenantId, MemberStatus.ACTIVE);
        List<Row> rows = new ArrayList<>();
        for (Device reader : readers) {
            rows.addAll(seed(reader));
            rows.addAll(classify(reader, people, runId));
        }
        rows.addAll(collisions(readers));
        return new BootstrapReport(runId, List.copyOf(rows));
    }

    /**
     * Taken before a trusted roster is recorded, so the roster and the seed that follows it hold
     * the reader lock before any id this reader allocates from.
     */
    @Transactional
    public void lockForRoster(Device reader) {
        reconcile.lockReader(reader);
    }

    /**
     * A complete roster, empty or not. Every active member this reader has no mapping for is
     * written with the existing desired-state writer, under an id above every id the reader showed.
     * Existing mappings and people the server does not know stay as they are, and nobody is linked.
     * A later roster writes only members still missing. Members who may be one of the people this
     * reader shows undecided are held back, as for every seed.
     */
    @Transactional
    public void seedFromTrustedRoster(Device reader) {
        reconcile.reconcile(reader);
    }

    private List<Row> seed(Device reader) {
        if (!reader.isRosterTrustedEmpty() || !observedUsers.findByDeviceId(reader.getId()).isEmpty()) {
            return List.of();
        }
        reconcile.reconcile(reader);
        return seededRows(reader);
    }

    private List<Row> seededRows(Device reader) {
        List<Row> rows = new ArrayList<>();
        for (MemberDeviceMapping mapping : mappings.findByDeviceId(reader.getId())) {
            Member member = members.findById(mapping.getMemberId()).orElse(null);
            if (member == null) {
                continue;
            }
            rows.add(row(SEEDED, reader, mapping.getDeviceUserId(), member.getPublicId(), null, member.getFullName(), null));
        }
        return rows;
    }

    private List<Row> classify(Device reader, List<Member> people, String runId) {
        List<Row> rows = new ArrayList<>();
        for (MemberDeviceMapping mapping : mappings.findByDeviceId(reader.getId())) {
            Row mapped = mapped(reader, mapping, runId);
            if (mapped != null) {
                rows.add(mapped);
            }
        }
        for (PendingEnrollment enrollment : enrollments.findByDeviceId(reader.getId())) {
            rows.add(unlinked(reader, enrollment, people, runId));
        }
        return rows;
    }

    private Row mapped(Device reader, MemberDeviceMapping mapping, String runId) {
        DeviceObservedUser observed = observedUsers
                .findByDeviceIdAndDeviceUserId(reader.getId(), mapping.getDeviceUserId())
                .orElse(null);
        if (observed == null) {
            return null;
        }
        Member member = members.findById(mapping.getMemberId()).orElse(null);
        if (member == null) {
            return null;
        }
        String readerName = ReaderReviewService.shown(observed.getReaderName(), observed.getReaderNameEx());
        String serverName = member.getFullName();
        if (readerName.equals(serverName)) {
            return row(MATCHING, reader, mapping.getDeviceUserId(), member.getPublicId(), readerName, serverName, null);
        }
        reviews.findByDeviceIdAndDeviceUserIdAndMemberId(reader.getId(), mapping.getDeviceUserId(),
                mapping.getMemberId()).ifPresent(item -> {
            item.assignBootstrapRun(runId);
            reviews.save(item);
        });
        return row(DIFFERENT, reader, mapping.getDeviceUserId(), member.getPublicId(), readerName, serverName, null);
    }

    private Row unlinked(Device reader, PendingEnrollment enrollment, List<Member> people, String runId) {
        enrollment.assignBootstrapRun(runId);
        enrollments.save(enrollment);
        DeviceObservedUser observed = observedUsers
                .findByDeviceIdAndDeviceUserId(reader.getId(), enrollment.getDeviceUserId())
                .orElse(null);
        String readerName = observed == null
                ? enrollment.getDeviceUserId()
                : ReaderReviewService.shown(observed.getReaderName(), observed.getReaderNameEx());
        return row(UNLINKED, reader, enrollment.getDeviceUserId(), null, readerName, null,
                suggestion(reader, readerName, people));
    }

    private String suggestion(Device reader, String readerName, List<Member> people) {
        List<Member> named = new ArrayList<>();
        for (Member member : people) {
            if (readerName.equals(member.getFullName()) && onAnotherId(reader, member)) {
                named.add(member);
            }
        }
        return named.size() == 1 ? named.get(0).getPublicId() : null;
    }

    private boolean onAnotherId(Device reader, Member member) {
        for (MemberDeviceMapping mapping : mappings.findByMemberId(member.getId())) {
            if (!reader.getId().equals(mapping.getDeviceId())) {
                return true;
            }
        }
        return false;
    }

    private List<Row> collisions(List<Device> readers) {
        Map<String, List<Sight>> byId = new LinkedHashMap<>();
        for (Device reader : readers) {
            for (DeviceObservedUser observed : observedUsers.findByDeviceId(reader.getId())) {
                Long memberId = mappings.findByDeviceIdAndDeviceUserId(reader.getId(), observed.getDeviceUserId())
                        .map(MemberDeviceMapping::getMemberId)
                        .orElse(null);
                byId.computeIfAbsent(observed.getDeviceUserId(), ignored -> new ArrayList<>())
                        .add(new Sight(
                                reader,
                                ReaderReviewService.shown(observed.getReaderName(), observed.getReaderNameEx()),
                                memberId));
            }
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<Sight>> entry : byId.entrySet()) {
            rows.addAll(collisionRows(entry.getKey(), entry.getValue()));
        }
        return rows;
    }

    private List<Row> collisionRows(String deviceUserId, List<Sight> sights) {
        if (sights.size() < 2 || !differentPeople(sights)) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        for (Sight sight : sights) {
            String memberId = sight.memberId() == null
                    ? null : members.findById(sight.memberId()).map(Member::getPublicId).orElse(null);
            rows.add(row(COLLISION, sight.reader(), deviceUserId, memberId, sight.name(), null, null));
        }
        return rows;
    }

    private static boolean differentPeople(List<Sight> sights) {
        List<String> names = sights.stream().map(Sight::name).distinct().toList();
        List<Long> memberIds = sights.stream().map(Sight::memberId).filter(id -> id != null).distinct().toList();
        return names.size() > 1 || memberIds.size() > 1;
    }

    private static Row row(String outcome, Device reader, String deviceUserId, String memberId,
                           String readerName, String serverName, String suggestionMemberId) {
        return new Row(outcome, reader.getPublicId(), deviceUserId, memberId, readerName, serverName,
                suggestionMemberId);
    }

    private record Sight(Device reader, String name, Long memberId) {
    }
}
