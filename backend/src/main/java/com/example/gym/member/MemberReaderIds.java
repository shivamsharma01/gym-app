package com.example.gym.member;

import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.member.dto.MemberResponse.ReaderUserId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Per-reader device user ids for member list/detail, one column per tenant reader. */
@Component
public class MemberReaderIds {

    private final DeviceRepository devices;
    private final MemberDeviceMappingRepository mappings;

    public MemberReaderIds(DeviceRepository devices, MemberDeviceMappingRepository mappings) {
        this.devices = devices;
        this.mappings = mappings;
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
        Map<Long, Map<Long, String>> byMember = new HashMap<>();
        for (MemberDeviceMapping mapping : mappings.findByMemberIdIn(memberIds)) {
            byMember
                    .computeIfAbsent(mapping.getMemberId(), id -> new HashMap<>())
                    .put(mapping.getDeviceId(), mapping.getDeviceUserId());
        }
        Map<Long, List<ReaderUserId>> out = new HashMap<>();
        for (Member member : members) {
            Map<Long, String> onReaders = byMember.getOrDefault(member.getId(), Map.of());
            List<ReaderUserId> rows = new ArrayList<>(readers.size());
            for (Device device : readers) {
                rows.add(new ReaderUserId(
                        device.getPublicId(),
                        device.getName(),
                        onReaders.get(device.getId())));
            }
            out.put(member.getId(), List.copyOf(rows));
        }
        return out;
    }
}
