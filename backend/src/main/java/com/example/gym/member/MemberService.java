package com.example.gym.member;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.DeviceAuthorizationService;
import com.example.gym.device.ReaderReconcileService;
import com.example.gym.member.dto.MemberRequests.CreateMember;
import com.example.gym.member.dto.MemberRequests.UpdateMember;
import com.example.gym.security.SecurityUtils;
import com.example.gym.tenant.TenantGuard;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final MemberNumbers numbers;
    private final ReaderReconcileService readers;

    public MemberService(MemberRepository memberRepository, AuditService auditService,
                         DeviceAuthorizationService deviceAuthorizationService,
                         MemberNumbers numbers, ReaderReconcileService readers) {
        this.memberRepository = memberRepository;
        this.auditService = auditService;
        this.deviceAuthorizationService = deviceAuthorizationService;
        this.numbers = numbers;
        this.readers = readers;
    }

    @Transactional(readOnly = true)
    public Page<Member> search(Long tenantId, String query, MemberStatus status,
                               DeviceAuthority deviceAuthority, Pageable pageable) {
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.isEmpty()) {
            return memberRepository.search(tenantId, null, null, status, deviceAuthority, pageable);
        }
        // Spaces are kept between words (and one at the end) so "kunal " narrows to that first name.
        String name = query.stripLeading().replaceAll("\\s+", " ");
        return memberRepository.search(tenantId, trimmed, name, status, deviceAuthority, pageable);
    }

    @Transactional(readOnly = true)
    public Member getById(Long id, Long tenantId) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> CommonExceptions.notFound("Member"));
        TenantGuard.check(member.getTenantId(), tenantId, "Member");
        return member;
    }

    @Transactional(readOnly = true)
    public Member getByPublicId(String publicId, Long tenantId) {
        Member member = memberRepository.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Member"));
        TenantGuard.check(member.getTenantId(), tenantId, "Member");
        return member;
    }

    /** Persists the member without sending them to a reader. The caller provisions or projects. */
    @Transactional
    public Member saveNew(CreateMember request, Long tenantId) {
        String code = StringUtils.hasText(request.memberCode())
                ? request.memberCode().trim()
                : numbers.newMemberCode(tenantId);
        if (memberRepository.existsByTenantIdAndMemberCode(tenantId, code)) {
            throw CommonExceptions.conflict("Member code already exists");
        }

        Member member = new Member(tenantId, code, request.firstName());
        member.setLastName(request.lastName());
        member.setEmail(request.email());
        member.setPhone(request.phone());
        member.setDateOfBirth(request.dateOfBirth());
        member.setGender(request.gender());
        member.setNotes(request.notes());
        if (StringUtils.hasText(request.deviceAuthority())) {
            DeviceAuthority authority = DeviceAuthority.fromString(request.deviceAuthority());
            if (authority != member.getDeviceAuthority()) {
                requireAuthorityManager();
            }
            member.setDeviceAuthority(authority);
        }
        Member saved = memberRepository.save(member);
        FlowLog.info("member", "created id={} code={}", saved.getPublicId(), saved.getMemberCode());
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("memberCode", saved.getMemberCode());
        auditService.record(AuditActions.MEMBER_CREATED, AuditActions.RESULT_SUCCESS,
                "Member", saved.getPublicId(), created);
        return saved;
    }

    @Transactional
    public Member update(String publicId, UpdateMember request, Long tenantId) {
        Member member = getByPublicId(publicId, tenantId);
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
                requireAuthorityManager();
                member.setDeviceAuthority(newAuth);
                authorityChanged = true;
            }
        }
        boolean nameChanged = !previousName.equals(member.getFullName());
        if (nameChanged || authorityChanged) {
            member.setProfileChangedAt(Instant.now());
        }
        Member saved = memberRepository.save(member);
        if (nameChanged || authorityChanged) {
            deviceAuthorizationService.publishProfile(saved);
        }
        FlowLog.info("member", "updated id={} nameChanged={} authorityChanged={}",
                saved.getPublicId(), nameChanged, authorityChanged);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("nameChanged", nameChanged);
        auditService.record(AuditActions.MEMBER_UPDATED, AuditActions.RESULT_SUCCESS,
                "Member", saved.getPublicId(), details);
        return saved;
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
                "Member", member.getPublicId(), Map.of("memberCode", member.getMemberCode()));
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
        readers.reconcile(saved);
        deviceAuthorizationService.syncMember(saved);
        FlowLog.info("member", "reactivated id={}", saved.getPublicId());
        auditService.record(AuditActions.MEMBER_REACTIVATED, AuditActions.RESULT_SUCCESS,
                "Member", saved.getPublicId(), Map.of("memberCode", saved.getMemberCode()));
        return saved;
    }

    /** Reader authority is changed only by those who may change it on its own endpoint. */
    private static void requireAuthorityManager() {
        boolean allowed = SecurityUtils.currentPrincipal().getAuthorities().stream()
                .anyMatch(a -> "USER_MANAGE".equals(a.getAuthority()) || "ROLE_GYM_ADMIN".equals(a.getAuthority()));
        if (!allowed) {
            throw CommonExceptions.forbidden("Only a gym admin can change a member's terminal authority");
        }
    }

    @Transactional
    public Member updateAuthority(String publicId, DeviceAuthority authority, Long tenantId) {
        Member member = getByPublicId(publicId, tenantId);
        if (member.getDeviceAuthority() != authority) {
            member.setDeviceAuthority(authority);
            member.setProfileChangedAt(Instant.now());
            Member saved = memberRepository.save(member);
            deviceAuthorizationService.publishProfile(saved);
            FlowLog.info("member", "authority updated id={} authority={}", saved.getPublicId(), authority);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("memberCode", saved.getMemberCode());
            details.put("deviceAuthority", authority.name());
            auditService.record(AuditActions.MEMBER_UPDATED, AuditActions.RESULT_SUCCESS,
                    "Member", saved.getPublicId(), details);
            return saved;
        }
        return member;
    }
}
