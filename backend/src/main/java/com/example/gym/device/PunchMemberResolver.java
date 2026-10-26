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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who a punch belongs to. The reader's own mapping wins. With no mapping, the photo this reader
 * shows at that id credits the punch when exactly one member has that photo.
 */
@Component
public class PunchMemberResolver {

    private static final Logger log = LoggerFactory.getLogger(PunchMemberResolver.class);

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

    /**
     * Fills punches stored before this reader's photo was known. Already linked punches stay put.
     * Runs in its own transaction so a deadlock or lock wait cannot roll back the observation that
     * just saved the person. A link failure is logged and left for a later claim or punch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void creditEarlierPunches(Device device, String deviceUserId) {
        Long memberId = memberId(device, deviceUserId);
        if (memberId == null) {
            return;
        }
        try {
            attendance.linkUnmatched(device.getTenantId(), device.getId(), deviceUserId, memberId);
        } catch (CannotAcquireLockException | DeadlockLoserDataAccessException ex) {
            log.warn("Could not link earlier punches for reader {} user {} (will retry later): {}",
                    device.getPublicId(), deviceUserId, ex.getMessage());
        } catch (RuntimeException ex) {
            if (isLockFailure(ex)) {
                log.warn("Could not link earlier punches for reader {} user {} (will retry later): {}",
                        device.getPublicId(), deviceUserId, ex.getMessage());
                return;
            }
            throw ex;
        }
    }

    private static boolean isLockFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && (message.contains("Deadlock") || message.contains("Lock wait timeout"))) {
                return true;
            }
        }
        return false;
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
