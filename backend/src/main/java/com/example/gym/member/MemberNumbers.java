package com.example.gym.member;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member codes (server identity, generated) and serial numbers (the id staff use on readers).
 * A serial is free only when no other member has it as a serial and no reader holds it for
 * someone else, including a reader that is still moving a member to it.
 */
@Component
public class MemberNumbers {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_CODE_ATTEMPTS = 10;
    private static final Pattern POSITIVE_INT = Pattern.compile("[1-9]\\d{0,8}");

    private final MemberRepository memberRepository;
    private final MemberDeviceMappingRepository mappingRepository;
    private final SecureRandom random = new SecureRandom();

    public MemberNumbers(MemberRepository memberRepository, MemberDeviceMappingRepository mappingRepository) {
        this.memberRepository = memberRepository;
        this.mappingRepository = mappingRepository;
    }

    /** A new, unused member code in the app's format (codes are never typed by staff). */
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

    /** The smallest positive integer no member and no reader in the tenant uses. */
    @Transactional(readOnly = true)
    public String nextSerial(Long tenantId) {
        Set<Integer> used = new HashSet<>();
        addNumbers(used, memberRepository.findSerialNumbers(tenantId));
        addNumbers(used, memberRepository.findCodesWithoutSerial(tenantId));
        addNumbers(used, mappingRepository.findDeviceUserIds(tenantId));
        addNumbers(used, mappingRepository.findPendingDeviceUserIds(tenantId));
        int next = 1;
        while (used.contains(next)) {
            next++;
        }
        return Integer.toString(next);
    }

    /** True when the serial belongs to someone other than {@code memberId} (null for a new member). */
    @Transactional(readOnly = true)
    public boolean isTaken(Long tenantId, String serial, Long memberId) {
        if (memberRepository.findByTenantIdAndSerialNumber(tenantId, serial)
                .filter(m -> !Objects.equals(m.getId(), memberId)).isPresent()) {
            return true;
        }
        if (memberRepository.findByTenantIdAndMemberCode(tenantId, serial)
                .filter(m -> m.getSerialNumber() == null && !Objects.equals(m.getId(), memberId)).isPresent()) {
            return true;
        }
        for (MemberDeviceMapping mapping : mappingRepository.findHolding(tenantId, serial)) {
            if (!Objects.equals(mapping.getMemberId(), memberId)) {
                return true;
            }
        }
        return false;
    }

    /** The serial a device user id would get on import, or null when someone else already has it. */
    @Transactional(readOnly = true)
    public String serialForImport(Long tenantId, String deviceUserId) {
        if (deviceUserId == null || deviceUserId.isBlank() || deviceUserId.length() > 32) {
            return null;
        }
        return memberRepository.findByTenantIdAndSerialNumber(tenantId, deviceUserId).isPresent()
                ? null : deviceUserId;
    }

    private static void addNumbers(Set<Integer> used, Iterable<String> values) {
        for (String value : values) {
            if (value != null && POSITIVE_INT.matcher(value.trim()).matches()) {
                used.add(Integer.parseInt(value.trim()));
            }
        }
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
