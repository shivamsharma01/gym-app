package com.example.gym.member;

import com.example.gym.device.ReaderFacePresence;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceReviewItemRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.member.dto.MemberResponse.ReaderUserId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Per-reader device user ids and sync markers for member list/detail. */
@Component
public class MemberReaderIds {

    private final DeviceRepository devices;
    private final MemberDeviceMappingRepository mappings;
    private final DesiredMemberProjectionRepository projections;
    private final DeviceReviewItemRepository reviews;
    private final ReaderFacePresence seenOnReader;

    public MemberReaderIds(DeviceRepository devices,
                           MemberDeviceMappingRepository mappings,
                           DesiredMemberProjectionRepository projections,
                           DeviceReviewItemRepository reviews,
                           ReaderFacePresence seenOnReader) {
        this.devices = devices;
        this.mappings = mappings;
        this.projections = projections;
        this.reviews = reviews;
        this.seenOnReader = seenOnReader;
    }

    /**
     * True when two or more readers already show an id for this member and those ids are not all
     * the same. A reader with no id yet does not count.
     */
    public static boolean idsDiffer(List<ReaderUserId> readers) {
        Set<String> ids = new HashSet<>();
        for (ReaderUserId reader : readers) {
            if (reader.deviceUserId() != null && !reader.deviceUserId().isBlank()) {
                ids.add(reader.deviceUserId());
            }
        }
        return ids.size() > 1;
    }

    public List<ReaderUserId> of(Member member) {
        return ofAll(List.of(member), member.getTenantId()).getOrDefault(member.getId(), List.of());
    }

    public Map<Long, List<ReaderUserId>> ofAll(List<Member> members, Long tenantId) {
        if (members.isEmpty()) {
            return Map.of();
        }
        List<Device> readers = devices.findByTenantId(tenantId).stream()
                .sorted(Comparator.comparing(Device::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<Long> memberIds = members.stream().map(Member::getId).toList();
        Set<Long> memberIdSet = new HashSet<>(memberIds);
        Map<Long, Map<Long, String>> byMember = new HashMap<>();
        for (MemberDeviceMapping mapping : mappings.findByMemberIdIn(memberIds)) {
            byMember
                    .computeIfAbsent(mapping.getMemberId(), id -> new HashMap<>())
                    .put(mapping.getDeviceId(), mapping.getDeviceUserId());
        }
        Map<Long, Map<Long, DesiredMemberProjection>> projByMember = new HashMap<>();
        for (DesiredMemberProjection row : projections.findByMemberIdIn(memberIds)) {
            projByMember
                    .computeIfAbsent(row.getMemberId(), id -> new HashMap<>())
                    .put(row.getDeviceId(), row);
        }
        // Open reviews (including DEVICE_USER_CHANGED conflicts) count as "held" for the UI even when
        // the projection hold flag is only set on the observation-fence path.
        Map<Long, Set<Long>> openReviewDevices = new HashMap<>();
        for (DeviceReviewItem item : reviews.findByTenantIdAndResolvedFalse(tenantId)) {
            if (item.getMemberId() == null || !memberIdSet.contains(item.getMemberId())) {
                continue;
            }
            openReviewDevices
                    .computeIfAbsent(item.getMemberId(), id -> new HashSet<>())
                    .add(item.getDeviceId());
        }
        Map<Long, Map<Long, ReaderFacePresence.Sight>> seen = seenOnReader.forMembers(tenantId, memberIds);
        Map<Long, List<ReaderUserId>> out = new HashMap<>();
        for (Member member : members) {
            Map<Long, String> onReaders = byMember.getOrDefault(member.getId(), Map.of());
            Map<Long, DesiredMemberProjection> rows = projByMember.getOrDefault(member.getId(), Map.of());
            Map<Long, ReaderFacePresence.Sight> seenHere = seen.getOrDefault(member.getId(), Map.of());
            Set<Long> needsReview = openReviewDevices.getOrDefault(member.getId(), Set.of());
            List<ReaderUserId> list = new ArrayList<>(readers.size());
            for (Device device : readers) {
                String deviceUserId = onReaders.get(device.getId());
                ReaderFacePresence.Sight sight = deviceUserId == null ? seenHere.get(device.getId()) : null;
                String shownId = deviceUserId != null ? deviceUserId : sight == null ? null : sight.deviceUserId();
                String state = deviceUserId == null && sight != null
                        ? "seen"
                        : syncState(deviceUserId, rows.get(device.getId()), needsReview.contains(device.getId()));
                list.add(new ReaderUserId(device.getPublicId(), device.getName(), shownId, state));
            }
            out.put(member.getId(), List.copyOf(list));
        }
        return out;
    }

    /**
     * Compact sync marker for list UI: {@code none}, {@code ok}, {@code pending}, {@code held},
     * {@code failed}, {@code absent}, or {@code seen} when the caller already chose that.
     */
    static String syncState(String deviceUserId, DesiredMemberProjection row, boolean openReview) {
        if (deviceUserId == null) {
            return "none";
        }
        if (row == null) {
            return openReview ? "held" : "pending";
        }
        if (row.isHeld() || openReview) {
            return "held";
        }
        if (row.getApplyError() != null) {
            return "failed";
        }
        if (!row.isPresentOnReader()) {
            return "absent";
        }
        if (!row.isApplied()) {
            return "pending";
        }
        return "ok";
    }
}
