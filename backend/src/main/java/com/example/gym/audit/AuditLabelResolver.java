package com.example.gym.audit;

import com.example.gym.device.domain.Device;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.payment.Payment;
import com.example.gym.payment.PaymentRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Resolves human-readable labels for audit resourceType + resourceId. */
@Component
public class AuditLabelResolver {

    private final MemberRepository memberRepository;
    private final MembershipRepository membershipRepository;
    private final PaymentRepository paymentRepository;
    private final DeviceRepository deviceRepository;
    private final JsonMapper jsonMapper;

    public AuditLabelResolver(MemberRepository memberRepository,
                              MembershipRepository membershipRepository,
                              PaymentRepository paymentRepository,
                              DeviceRepository deviceRepository,
                              JsonMapper jsonMapper) {
        this.memberRepository = memberRepository;
        this.membershipRepository = membershipRepository;
        this.paymentRepository = paymentRepository;
        this.deviceRepository = deviceRepository;
        this.jsonMapper = jsonMapper;
    }

    public String resolve(String resourceType, String resourceId, String detailsJson) {
        if (!StringUtils.hasText(resourceType) || !StringUtils.hasText(resourceId)) {
            return null;
        }
        return switch (resourceType) {
            case "Member" -> memberRepository.findByPublicId(resourceId)
                    .map(m -> m.getFullName() + " (" + m.getMemberCode() + ")")
                    .or(() -> detailField(detailsJson, "memberCode"))
                    .orElse(shortId(resourceId));
            case "Membership" -> membershipRepository.findByPublicIdAndDeletedFalse(resourceId)
                    .map(this::membershipLabel)
                    .orElse(shortId(resourceId));
            case "Payment" -> paymentRepository.findByPublicId(resourceId)
                    .map(this::paymentLabel)
                    .orElse(shortId(resourceId));
            case "Device" -> deviceRepository.findByPublicId(resourceId)
                    .map(Device::getName)
                    .orElse(shortId(resourceId));
            default -> detailField(detailsJson, "memberCode")
                    .or(() -> detailField(detailsJson, "name"))
                    .orElse(shortId(resourceId));
        };
    }

    private String membershipLabel(Membership m) {
        String memberName = memberRepository.findById(m.getMemberId())
                .map(Member::getFullName)
                .orElse("Member");
        return memberName + " · " + m.getPlanName();
    }

    private String paymentLabel(Payment p) {
        String memberName = memberRepository.findById(p.getMemberId())
                .map(Member::getFullName)
                .orElse("Member");
        return memberName + " · " + p.getAmount().toPlainString() + " " + p.getCurrency()
                + (p.getPaidOn() == null ? "" : " · " + p.getPaidOn());
    }

    private Optional<String> detailField(String detailsJson, String field) {
        if (!StringUtils.hasText(detailsJson)) {
            return Optional.empty();
        }
        try {
            JsonNode node = jsonMapper.readTree(detailsJson).get(field);
            if (node == null || node.isNull() || !StringUtils.hasText(node.asString())) {
                return Optional.empty();
            }
            return Optional.of(node.asString());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8) + "…";
    }
}
