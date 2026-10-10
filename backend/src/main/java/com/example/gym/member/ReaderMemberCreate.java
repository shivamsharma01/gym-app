package com.example.gym.member;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.domain.Device;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceService;
import com.example.gym.member.dto.MemberRequests.CreateMember;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff create of a member who has a face. The member, the face, and one mapping and desired
 * projection per reader of the gym commit together. Each reader allocates its own device user id.
 * A gym with no reader still saves the member and publishes nothing.
 */
@Service
public class ReaderMemberCreate {

    private final MemberService members;
    private final MemberFaceService faces;
    private final DesiredProjectionService projections;
    private final DeviceRepository devices;

    public ReaderMemberCreate(MemberService members,
                              MemberFaceService faces,
                              DesiredProjectionService projections,
                              DeviceRepository devices) {
        this.members = members;
        this.faces = faces;
        this.projections = projections;
        this.devices = devices;
    }

    @Transactional
    public Member create(CreateMember request, Long tenantId, byte[] rawFace) {
        if (rawFace == null || rawFace.length == 0) {
            throw CommonExceptions.badRequest("A face photo is required");
        }
        Member saved = members.saveNew(request, tenantId);
        MemberFace face = faces.storeUploaded(saved, rawFace);
        for (Device reader : readers(tenantId)) {
            projections.write(saved, reader, face);
        }
        return saved;
    }

    /** Lowest device id first: each reader's revision lock is taken in that order. */
    private List<Device> readers(Long tenantId) {
        return devices.findByTenantId(tenantId).stream()
                .filter(device -> device.getGatewayId() != null)
                .sorted(Comparator.comparing(Device::getId))
                .toList();
    }
}
