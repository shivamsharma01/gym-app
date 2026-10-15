package com.example.gym.member;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.ReaderReconcileService;
import com.example.gym.face.MemberFaceService;
import com.example.gym.member.dto.MemberRequests.CreateMember;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff create of a member who has a face. The member, the face, and one mapping and desired
 * projection per reader of the gym commit together. Each reader allocates its own device user id.
 * A gym with no reader still saves the member and publishes nothing. The readers are written as
 * every seed is: a reader showing someone undecided who may be this member is left for staff.
 */
@Service
public class ReaderMemberCreate {

    private final MemberService members;
    private final MemberFaceService faces;
    private final ReaderReconcileService readers;

    public ReaderMemberCreate(MemberService members,
                              MemberFaceService faces,
                              ReaderReconcileService readers) {
        this.members = members;
        this.faces = faces;
        this.readers = readers;
    }

    @Transactional
    public Member create(CreateMember request, Long tenantId, byte[] rawFace) {
        if (rawFace == null || rawFace.length == 0) {
            throw CommonExceptions.badRequest("A face photo is required");
        }
        Member saved = members.saveNew(request, tenantId);
        faces.storeUploaded(saved, rawFace);
        readers.reconcile(saved);
        return saved;
    }
}
