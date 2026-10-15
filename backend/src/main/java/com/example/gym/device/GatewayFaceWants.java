package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import com.example.gym.face.MemberFaceService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reader photos the server needs from the gateway: those of people a reader shows that staff
 * have not decided on, when no unused upload of the same photo is held. Creating or importing such
 * a person takes the photo from that upload. Unused uploads are purged after a day, so a photo can
 * be wanted again while its person is still undecided.
 */
@Service
public class GatewayFaceWants {

    private final DeviceRepository devices;
    private final PendingEnrollmentRepository enrollments;
    private final DeviceObservedUserRepository observedUsers;
    private final MemberFaceService faces;

    public GatewayFaceWants(DeviceRepository devices,
                            PendingEnrollmentRepository enrollments,
                            DeviceObservedUserRepository observedUsers,
                            MemberFaceService faces) {
        this.devices = devices;
        this.enrollments = enrollments;
        this.observedUsers = observedUsers;
        this.faces = faces;
    }

    @Transactional(readOnly = true)
    public List<String> wanted(Gateway gateway, String devicePublicId) {
        Device device = devices.findByPublicId(devicePublicId)
                .filter(found -> gateway.getId().equals(found.getGatewayId()))
                .orElseThrow(() -> CommonExceptions.notFound("Reader"));
        Set<String> wanted = new LinkedHashSet<>();
        for (PendingEnrollment enrollment : enrollments.findByDeviceIdAndResolvedFalse(device.getId())) {
            observedUsers.findByDeviceIdAndDeviceUserId(device.getId(), enrollment.getDeviceUserId())
                    .map(DeviceObservedUser::getFaceSha256)
                    .filter(sha -> sha != null && sha.length() == 64)
                    .map(sha -> sha.toLowerCase(Locale.ROOT))
                    .filter(sha -> faces.findUnconsumedByHash(device.getTenantId(), sha).isEmpty())
                    .ifPresent(wanted::add);
        }
        return List.copyOf(wanted);
    }
}
