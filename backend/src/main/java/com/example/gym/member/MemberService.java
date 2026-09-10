package com.example.gym.member;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.member.dto.MemberRequests.CreateMember;
import com.example.gym.member.dto.MemberRequests.UpdateMember;
import com.example.gym.tenant.TenantGuard;
import java.security.SecureRandom;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MemberService {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_CODE_ATTEMPTS = 10;

    private final MemberRepository memberRepository;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public MemberService(MemberRepository memberRepository, AuditService auditService) {
        this.memberRepository = memberRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<Member> search(Long tenantId, String query, MemberStatus status, Pageable pageable) {
        return memberRepository.search(tenantId, query, status, pageable);
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
                : generateUniqueCode(tenantId);
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
        Member saved = memberRepository.save(member);

        auditService.record(AuditActions.MEMBER_CREATED, AuditActions.RESULT_SUCCESS,
                "Member", saved.getPublicId(), Map.of("memberCode", saved.getMemberCode()));
        return saved;
    }

    @Transactional
    public Member update(String publicId, UpdateMember request, Long tenantId) {
        Member member = getByPublicId(publicId, tenantId);
        member.setFirstName(request.firstName());
        member.setLastName(request.lastName());
        member.setEmail(request.email());
        member.setPhone(request.phone());
        member.setDateOfBirth(request.dateOfBirth());
        member.setGender(request.gender());
        member.setNotes(request.notes());
        Member saved = memberRepository.save(member);
        auditService.record(AuditActions.MEMBER_UPDATED, AuditActions.RESULT_SUCCESS,
                "Member", saved.getPublicId(), null);
        return saved;
    }

    @Transactional
    public void deactivate(String publicId, Long tenantId) {
        Member member = getByPublicId(publicId, tenantId);
        member.setStatus(MemberStatus.INACTIVE);
        memberRepository.save(member);
        auditService.record(AuditActions.MEMBER_DELETED, AuditActions.RESULT_SUCCESS,
                "Member", member.getPublicId(), null);
    }

    private String generateUniqueCode(Long tenantId) {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = "MBR-" + randomCode();
            if (!memberRepository.existsByTenantIdAndMemberCode(tenantId, code)) {
                return code;
            }
        }
        throw CommonExceptions.conflict("Unable to allocate a unique member code; please retry");
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
