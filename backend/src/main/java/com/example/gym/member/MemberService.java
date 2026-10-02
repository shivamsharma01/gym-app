package com.example.gym.member;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.DeviceAuthorizationService;
import com.example.gym.device.MemberDeviceProvisioningService;
import com.example.gym.member.dto.MemberRequests.CreateMember;
import com.example.gym.member.dto.MemberRequests.UpdateMember;
import com.example.gym.tenant.TenantGuard;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final AuditService auditService;
    private final DeviceAuthorizationService deviceAuthorizationService;
    private final MemberDeviceProvisioningService provisioning;
    private final MemberNumbers numbers;

    public MemberService(MemberRepository memberRepository, AuditService auditService,
                         DeviceAuthorizationService deviceAuthorizationService,
                         MemberDeviceProvisioningService provisioning,
                         MemberNumbers numbers) {
        this.memberRepository = memberRepository;
        this.auditService = auditService;
        this.deviceAuthorizationService = deviceAuthorizationService;
        this.provisioning = provisioning;
        this.numbers = numbers;
    }

    @Transactional(readOnly = true)
    public Page<Member> search(Long tenantId, String query, MemberStatus status,
                               MemberCreationSource creationSource, Pageable pageable) {
        return memberRepository.search(tenantId, query, status, creationSource, pageable);
    }

    @Transactional(readOnly = true)
    public Member getByPublicId(String publicId, Long tenantId) {
        Member member = memberRepository.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Member"));
        TenantGuard.check(member.getTenantId(), tenantId, "Member");
        return member;
    }

    @Transactional
    public Member create(CreateMember request, Long tenantId) {
        String code = StringUtils.hasText(request.memberCode())
                ? request.memberCode().trim()
                : numbers.newMemberCode(tenantId);
        if (memberRepository.existsByTenantIdAndMemberCode(tenantId, code)) {
            throw CommonExceptions.conflict("Member code already exists");
        }
        String serial = StringUtils.hasText(request.serialNumber())
                ? request.serialNumber().trim()
                : numbers.nextSerial(tenantId);
        requireFreeSerial(tenantId, serial, null);

        Member member = new Member(tenantId, code, request.firstName());
        member.setSerialNumber(serial);
        member.setLastName(request.lastName());
        member.setEmail(request.email());
        member.setPhone(request.phone());
        member.setDateOfBirth(request.dateOfBirth());
        member.setGender(request.gender());
        member.setNotes(request.notes());
        member.setCreationSource(MemberCreationSource.MANUAL);
        if (StringUtils.hasText(request.deviceAuthority())) {
            member.setDeviceAuthority(DeviceAuthority.fromString(request.deviceAuthority()));
        }
        Member saved = memberRepository.save(member);
        provisioning.provisionMember(saved, Set.of());

        FlowLog.info("member", "created id={} code={} serial={}", saved.getPublicId(), saved.getMemberCode(), serial);
        auditService.record(AuditActions.MEMBER_CREATED, AuditActions.RESULT_SUCCESS,
                "Member", saved.getPublicId(),
                Map.of("memberCode", saved.getMemberCode(), "serialNumber", serial));
        return saved;
    }

    @Transactional
    public Member update(String publicId, UpdateMember request, Long tenantId) {
        Member member = getByPublicId(publicId, tenantId);
        String previousSerial = member.getSerialNumber();
        String newSerial = StringUtils.hasText(request.serialNumber()) ? request.serialNumber().trim() : null;
        boolean serialChanged = newSerial != null && !newSerial.equals(previousSerial);
        if (serialChanged) {
            requireFreeSerial(tenantId, newSerial, member.getId());
        }
        String previousName = member.getFullName();
        member.setFirstName(request.firstName());
        member.setLastName(request.lastName());
        member.setEmail(request.email());
        member.setPhone(request.phone());
        member.setDateOfBirth(request.dateOfBirth());
        member.setGender(request.gender());
        member.setNotes(request.notes());
        boolean authorityChanged = false;
        if (StringUtils.hasText(request.deviceAuthority())) {
            DeviceAuthority newAuth = DeviceAuthority.fromString(request.deviceAuthority());
            if (newAuth != member.getDeviceAuthority()) {
                member.setDeviceAuthority(newAuth);
                authorityChanged = true;
            }
        }
        boolean nameChanged = !previousName.equals(member.getFullName());
        if (nameChanged || authorityChanged || serialChanged) {
            member.setProfileChangedAt(Instant.now());
        }
        if (serialChanged) {
            // Gateways apply the old id's removal only when it is newer than their own copy.
            member.setAccessChangedAt(member.getProfileChangedAt());
            member.setSerialNumber(newSerial);
        }
        Member saved = memberRepository.save(member);
        if (serialChanged) {
            provisioning.moveToSerial(saved);
        }
        if (nameChanged || authorityChanged) {
            provisioning.pushProfile(saved, Set.of());
        }
        FlowLog.info("member", "updated id={} nameChanged={} serialChanged={}", saved.getPublicId(),
                nameChanged, serialChanged);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("nameChanged", nameChanged);
        details.put("serialNumber", saved.getSerialNumber());
        if (serialChanged) {
            details.put("previousSerialNumber", previousSerial);
        }
        auditService.record(AuditActions.MEMBER_UPDATED, AuditActions.RESULT_SUCCESS,
                "Member", saved.getPublicId(), details);
        return saved;
    }

    /** The number the create form starts with. */
    @Transactional(readOnly = true)
    public String nextSerial(Long tenantId) {
        return numbers.nextSerial(tenantId);
    }

    private void requireFreeSerial(Long tenantId, String serial, Long memberId) {
        if (numbers.isTaken(tenantId, serial, memberId)) {
            throw CommonExceptions.conflict(
                    "Serial number " + serial + " is already used by another member or on a reader");
        }
    }

    @Transactional
    public void deactivate(String publicId, Long tenantId) {
        Member member = getByPublicId(publicId, tenantId);
        member.setStatus(MemberStatus.INACTIVE);
        member.setAccessChangedAt(Instant.now());
        memberRepository.save(member);
        deviceAuthorizationService.syncMember(member);
        FlowLog.info("member", "deactivated id={}", member.getPublicId());
        auditService.record(AuditActions.MEMBER_DELETED, AuditActions.RESULT_SUCCESS,
                "Member", member.getPublicId(), serialDetails(member));
    }

    @Transactional
    public Member reactivate(String publicId, Long tenantId) {
        Member member = getByPublicId(publicId, tenantId);
        if (member.getStatus() == MemberStatus.ACTIVE) {
            return member;
        }
        member.setStatus(MemberStatus.ACTIVE);
        member.setAccessChangedAt(Instant.now());
        Member saved = memberRepository.save(member);
        deviceAuthorizationService.syncMember(saved);
        provisioning.provisionMember(saved, Set.of());
        FlowLog.info("member", "reactivated id={}", saved.getPublicId());
        auditService.record(AuditActions.MEMBER_REACTIVATED, AuditActions.RESULT_SUCCESS,
                "Member", saved.getPublicId(), serialDetails(saved));
        return saved;
    }

    @Transactional
    public Member updateAuthority(String publicId, DeviceAuthority authority, Long tenantId) {
        Member member = getByPublicId(publicId, tenantId);
        if (member.getDeviceAuthority() != authority) {
            member.setDeviceAuthority(authority);
            member.setProfileChangedAt(Instant.now());
            Member saved = memberRepository.save(member);
            provisioning.pushProfile(saved, Set.of());
            FlowLog.info("member", "authority updated id={} authority={}", saved.getPublicId(), authority);
            Map<String, Object> details = serialDetails(saved);
            details.put("deviceAuthority", authority.name());
            auditService.record(AuditActions.MEMBER_UPDATED, AuditActions.RESULT_SUCCESS,
                    "Member", saved.getPublicId(), details);
            return saved;
        }
        return member;
    }

    private static Map<String, Object> serialDetails(Member member) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("serialNumber", member.getSerialNumber());
        return details;
    }
}
