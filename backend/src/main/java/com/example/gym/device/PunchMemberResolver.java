package com.example.gym.device;

import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.repo.AttendanceEventRepository;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who a punch belongs to. The reader's own mapping wins. With no mapping, the photo this reader
 * shows at that id credits the punch when exactly one member has that photo.
 */
@Component
public class PunchMemberResolver {

    private final MemberDeviceMappingRepository mappings;
    private final DeviceObservedUserRepository observedUsers;
    private final MemberFaceRepository faces;
    private final AttendanceEventRepository attendance;

    public PunchMemberResolver(MemberDeviceMappingRepository mappings,
                               DeviceObservedUserRepository observedUsers,
                               MemberFaceRepository faces,
                               AttendanceEventRepository attendance) {
        this.mappings = mappings;
        this.observedUsers = observedUsers;
        this.faces = faces;
        this.attendance = attendance;
    }

    public Long memberId(Device device, String deviceUserId) {
        if (device == null || deviceUserId == null || deviceUserId.isBlank()) {
            return null;
        }
        MemberDeviceMapping mapping = mappings.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .or(() -> mappings.findFirstByDeviceIdAndPendingDeviceUserId(device.getId(), deviceUserId))
                .orElse(null);
        if (mapping != null) {
            return mapping.getMemberId();
        }
        return memberByPhoto(device, deviceUserId);
    }

    /** Fills punches stored before this reader's photo was known. Already linked punches stay put. */
    @Transactional
    public void creditEarlierPunches(Device device, String deviceUserId) {
        Long memberId = memberId(device, deviceUserId);
        if (memberId == null) {
            return;
        }
        attendance.linkUnmatched(device.getTenantId(), device.getId(), deviceUserId, memberId);
    }

    private Long memberByPhoto(Device device, String deviceUserId) {
        DeviceObservedUser seen = observedUsers.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElse(null);
        if (seen == null || seen.getFaceSha256() == null || seen.getFaceSha256().isBlank()) {
            return null;
        }
        List<Long> members = faces.findByTenantIdAndSha256(device.getTenantId(), seen.getFaceSha256()).stream()
                .map(MemberFace::getMemberId)
                .distinct()
                .toList();
        return members.size() == 1 ? members.get(0) : null;
    }
}
