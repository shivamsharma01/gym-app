package com.example.gym.reporting;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.AccessResult;
import com.example.gym.device.domain.Device;
import com.example.gym.device.repo.AttendanceEventRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import com.example.gym.payment.PaymentRepository;
import com.example.gym.payment.PaymentStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    private final MemberRepository memberRepository;
    private final MembershipRepository membershipRepository;
    private final PaymentRepository paymentRepository;
    private final AttendanceEventRepository attendanceEventRepository;
    private final DeviceRepository deviceRepository;

    public ReportService(MemberRepository memberRepository,
                         MembershipRepository membershipRepository,
                         PaymentRepository paymentRepository,
                         AttendanceEventRepository attendanceEventRepository,
                         DeviceRepository deviceRepository) {
        this.memberRepository = memberRepository;
        this.membershipRepository = membershipRepository;
        this.paymentRepository = paymentRepository;
        this.attendanceEventRepository = attendanceEventRepository;
        this.deviceRepository = deviceRepository;
    }

    @Transactional(readOnly = true)
    public ReportSummary summary(Long tenantId, LocalDate from, LocalDate to) {
        requireTenant(tenantId);
        LocalDate start = from == null ? LocalDate.now().minusDays(30) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        Instant fromInstant = start.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        LocalDate today = LocalDate.now();
        long expiring = membershipRepository.findByTenantId(tenantId).stream()
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .filter(m -> !m.getEndDate().isBefore(today) && !m.getEndDate().isAfter(today.plusDays(7)))
                .count();
        long expired = membershipRepository.findByTenantId(tenantId).stream()
                .filter(m -> m.getStatus() == MembershipStatus.EXPIRED
                        || (m.getStatus() == MembershipStatus.ACTIVE && m.getEndDate().isBefore(today)))
                .count();

        List<Map<String, Object>> devices = deviceRepository.findByTenantId(tenantId).stream()
                .map(this::deviceRow)
                .toList();

        return new ReportSummary(
                memberRepository.countByTenantId(tenantId),
                memberRepository.countByTenantIdAndStatus(tenantId, MemberStatus.ACTIVE),
                membershipRepository.countByTenantIdAndStatus(tenantId, MembershipStatus.ACTIVE),
                expiring,
                expired,
                paymentRepository.sumCompletedBetween(tenantId, start, end),
                paymentRepository.countByTenantIdAndStatusAndPaidOnBetween(
                        tenantId, PaymentStatus.COMPLETED, start, end),
                attendanceEventRepository.countByTenantIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        tenantId, fromInstant, toInstant),
                attendanceEventRepository.countByTenantIdAndResultAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        tenantId, AccessResult.DENIED, fromInstant, toInstant),
                devices);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> memberships(Long tenantId) {
        requireTenant(tenantId);
        return membershipRepository.findByTenantId(tenantId).stream()
                .map(this::membershipRow)
                .toList();
    }

    private Map<String, Object> deviceRow(Device device) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", device.getPublicId());
        row.put("name", device.getName());
        row.put("role", device.getRole().name());
        row.put("connectionState", device.getConnectionState().name());
        row.put("lastSeenAt", device.getLastSeenAt());
        return row;
    }

    private Map<String, Object> membershipRow(Membership m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", m.getPublicId());
        row.put("planName", m.getPlanName());
        row.put("status", m.getStatus().name());
        row.put("paymentStatus", m.getPaymentStatus().name());
        row.put("startDate", m.getStartDate());
        row.put("endDate", m.getEndDate());
        row.put("price", m.getPrice());
        row.put("currency", m.getCurrency());
        row.put("amountPaid", m.getAmountPaid());
        return row;
    }

    private void requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw CommonExceptions.badRequest("A gym tenant is required");
        }
    }
}
