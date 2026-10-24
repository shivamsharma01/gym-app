package com.example.gym.member;

import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Members whose Entry or Exit id, including one only seen on the reader, is exactly this text. */
@Component
public class MemberReaderSearch {

    private final MemberDeviceMappingRepository mappings;
    private final DeviceObservedUserRepository observedUsers;
    private final MemberRepository members;

    public MemberReaderSearch(MemberDeviceMappingRepository mappings,
                              DeviceObservedUserRepository observedUsers,
                              MemberRepository members) {
        this.mappings = mappings;
        this.observedUsers = observedUsers;
        this.members = members;
    }

    public List<Long> matching(Long tenantId, String query) {
        if (tenantId == null || query == null || query.isBlank() || !query.chars().allMatch(Character::isDigit)) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (MemberDeviceMapping mapping : mappings.findHolding(tenantId, query)) {
            ids.add(mapping.getMemberId());
        }
        for (DeviceObservedUser observed : observedUsers.findByTenantIdAndDeviceUserId(tenantId, query)) {
            String hash = observed.getFaceSha256();
            if (hash == null || hash.isBlank()) {
                continue;
            }
            List<Member> owners = members.findByTenantIdAndFaceSha256(tenantId, hash.toLowerCase(Locale.ROOT));
            if (owners.size() == 1) {
                ids.add(owners.get(0).getId());
            }
        }
        return List.copyOf(ids);
    }
}
