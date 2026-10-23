package com.example.gym.member;

import com.example.gym.common.error.CommonExceptions;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Member codes are the server identity. Staff do not type them, and they are not reader ids. */
@Component
public class MemberNumbers {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_CODE_ATTEMPTS = 10;

    private final MemberRepository memberRepository;
    private final SecureRandom random = new SecureRandom();

    public MemberNumbers(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /** A new, unused member code in the app's format. */
    @Transactional(readOnly = true)
    public String newMemberCode(Long tenantId) {
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
