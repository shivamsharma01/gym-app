package com.example.gym.member;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member codes (server identity, generated) and serial numbers (a staff label, not unique).
 * {@link #nextSerial} suggests an unused integer for optional staff fill; create leaves serial
 * null until device 1 ACK when staff omit it.
 */
@Component
public class MemberNumbers {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_CODE_ATTEMPTS = 10;
    private static final Pattern POSITIVE_INT = Pattern.compile("[1-9]\\d{0,8}");
    /** Same character set as create/update serial validation, non-blank. */
    private static final Pattern SERIAL_VALUE = Pattern.compile("[A-Za-z0-9_-]+");

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

    /** The smallest positive integer no member and no reader in the tenant uses (suggestion only). */
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

    /**
     * Serial to use when a member is created from a reader person: that reader's device user id,
     * or null when the id is not a usable serial string.
     */
    public String serialForImport(String deviceUserId) {
        if (deviceUserId == null || deviceUserId.isBlank() || deviceUserId.length() > 32) {
            return null;
        }
        String trimmed = deviceUserId.trim();
        return SERIAL_VALUE.matcher(trimmed).matches() ? trimmed : null;
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
