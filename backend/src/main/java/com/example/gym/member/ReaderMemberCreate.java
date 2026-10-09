package com.example.gym.member;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.domain.Device;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceService;
import com.example.gym.member.dto.MemberRequests.CreateMember;
import com.example.gym.tenant.TenantGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff create of a member who has a face, pointed at one reader. The member, the face, the
 * mapping, and the desired projection commit together. Other readers are not written.
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
    public Member create(CreateMember request, Long tenantId, String readerId, byte[] rawFace) {
        if (rawFace == null || rawFace.length == 0) {
            throw CommonExceptions.badRequest("A face photo is required");
        }
        Device device = devices.findByPublicId(readerId)
                .orElseThrow(() -> CommonExceptions.notFound("Reader"));
        TenantGuard.check(device.getTenantId(), tenantId, "Reader");
        if (device.getGatewayId() == null) {
            throw CommonExceptions.badRequest("Reader has no gateway");
        }
        Member saved = members.saveNew(request, tenantId);
        MemberFace face = faces.storeUploaded(saved, rawFace);
        projections.write(saved, device, face);
        return saved;
    }
}
