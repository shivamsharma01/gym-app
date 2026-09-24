package com.example.gym.reporting;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.AccessResult;
import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.domain.Device;
import com.example.gym.device.repo.AttendanceEventRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import com.example.gym.payment.Payment;
import com.example.gym.payment.PaymentRepository;
import com.example.gym.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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
        long expiring = membershipRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
                .filter(m -> m.effectiveStatus(today) == MembershipStatus.ACTIVE)
                .filter(m -> !m.getEndDate().isBefore(today) && !m.getEndDate().isAfter(today.plusDays(7)))
                .count();
        long expired = membershipRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
                .filter(m -> m.effectiveStatus(today) == MembershipStatus.EXPIRED)
                .count();

        List<Map<String, Object>> devices = deviceRepository.findByTenantId(tenantId).stream()
                .map(this::deviceRow)
                .toList();

        return new ReportSummary(
                memberRepository.countByTenantId(tenantId),
                memberRepository.countByTenantIdAndStatus(tenantId, MemberStatus.ACTIVE),
                membershipRepository.countByTenantIdAndStatusAndDeletedFalse(tenantId, MembershipStatus.ACTIVE),
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
    public ReportOperations operations(Long tenantId, LocalDate from, LocalDate to) {
        requireTenant(tenantId);
        LocalDate start = from == null ? LocalDate.now().minusDays(30) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (end.isBefore(start)) {
            throw CommonExceptions.badRequest("Report end date cannot be before start date");
        }

        LocalDate today = LocalDate.now();
        Instant fromInstant = start.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInstant = end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        List<Member> members = memberRepository.findAll().stream()
                .filter(m -> tenantId.equals(m.getTenantId()))
                .toList();
        Map<Long, Member> membersById = members.stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));

        List<Membership> memberships = membershipRepository.findByTenantIdAndDeletedFalse(tenantId);
        List<Payment> payments = paymentRepository
                .findByTenantIdAndPaidOnBetweenOrderByPaidOnDescIdDesc(tenantId, start, end);
        List<AttendanceEvent> attendance = attendanceEventRepository
                .findByTenantIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDesc(
                        tenantId, fromInstant, toInstant);

        List<Member> newMemberRows = members.stream()
                .filter(m -> !m.getJoinedOn().isBefore(start) && !m.getJoinedOn().isAfter(end))
                .sorted(Comparator.comparing(Member::getJoinedOn).reversed())
                .toList();

        long activeMembers = members.stream()
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .count();
        long activeMemberships = memberships.stream()
                .filter(m -> m.effectiveStatus(today) == MembershipStatus.ACTIVE)
                .count();

        List<Membership> expiring = memberships.stream()
                .filter(m -> m.effectiveStatus(today) == MembershipStatus.ACTIVE)
                .filter(m -> !m.getEndDate().isBefore(today) && !m.getEndDate().isAfter(today.plusDays(30)))
                .sorted(Comparator.comparing(Membership::getEndDate))
                .toList();

        List<Membership> outstanding = memberships.stream()
                .filter(m -> m.effectiveStatus(today) != MembershipStatus.CANCELLED)
                .filter(m -> balance(m).signum() > 0)
                .sorted(Comparator.comparing(Membership::getEndDate))
                .toList();

        List<Payment> completed = payments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.COMPLETED)
                .toList();
        BigDecimal collectionAmount = completed.stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, List<Payment>> byMethod = completed.stream()
                .collect(Collectors.groupingBy(p -> p.getMethod().name(), LinkedHashMap::new, Collectors.toList()));
        List<ReportOperations.CollectionByMethod> collectionsByMethod = byMethod.entrySet().stream()
                .map(e -> new ReportOperations.CollectionByMethod(
                        e.getKey(), e.getValue().size(),
                        e.getValue().stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)))
                .sorted(Comparator.comparing(ReportOperations.CollectionByMethod::amount).reversed())
                .toList();

        Set<Long> uniqueAttendees = attendance.stream()
                .filter(e -> e.getResult() == AccessResult.GRANTED)
                .map(AttendanceEvent::getMemberId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, List<AttendanceEvent>> attendanceByMember = attendance.stream()
                .filter(e -> e.getResult() == AccessResult.GRANTED && e.getMemberId() != null)
                .collect(Collectors.groupingBy(AttendanceEvent::getMemberId, LinkedHashMap::new, Collectors.toList()));

        List<ReportOperations.AttendanceRow> attendanceRows = attendanceByMember.entrySet().stream()
                .map(e -> {
                    Member member = membersById.get(e.getKey());
                    Instant last = e.getValue().stream().map(AttendanceEvent::getOccurredAt)
                            .max(Comparator.naturalOrder()).orElse(null);
                    return new ReportOperations.AttendanceRow(
                            member == null ? String.valueOf(e.getKey()) : member.getPublicId(),
                            member == null ? "" : member.getMemberCode(),
                            member == null ? "Unknown member" : member.getFullName(),
                            e.getValue().size(),
                            last == null ? "" : last.toString());
                })
                .sorted(Comparator.comparing(ReportOperations.AttendanceRow::visits).reversed())
                .toList();

        Set<Long> attendedMemberIds = uniqueAttendees;
        List<ReportOperations.MemberRow> inactiveMembers = members.stream()
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .filter(m -> !attendedMemberIds.contains(m.getId()))
                .sorted(Comparator.comparing(Member::getJoinedOn))
                .map(this::memberRow)
                .toList();

        long renewals = memberships.stream()
                .filter(m -> !m.getStartDate().isBefore(start) && !m.getStartDate().isAfter(end))
                .filter(m -> m.getStatus() != MembershipStatus.CANCELLED)
                .count();

        BigDecimal outstandingAmount = outstanding.stream()
                .map(this::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ReportOperations.Overview overview = new ReportOperations.Overview(
                members.size(), activeMembers, newMemberRows.size(), activeMemberships,
                expiring.stream().filter(m -> !m.getEndDate().isAfter(today.plusDays(7))).count(),
                expiring.size(),
                memberships.stream().filter(m -> m.effectiveStatus(today) == MembershipStatus.EXPIRED).count(),
                outstanding.size(), outstandingAmount,
                completed.size(), collectionAmount,
                attendance.stream().filter(e -> e.getResult() == AccessResult.GRANTED).count(),
                uniqueAttendees.size(),
                attendance.stream().filter(e -> e.getResult() == AccessResult.DENIED).count(),
                renewals);

        List<ReportOperations.PaymentRow> paymentRows = payments.stream()
                .map(p -> {
                    Member member = membersById.get(p.getMemberId());
                    return new ReportOperations.PaymentRow(
                            p.getPublicId(), memberName(member), memberCode(member), p.getPaidOn().toString(),
                            p.getAmount(), p.getCurrency(), p.getMethod().name(), p.getStatus().name());
                }).toList();

        List<ReportOperations.MembershipRow> expiringRows = expiring.stream()
                .map(m -> new ReportOperations.MembershipRow(
                        m.getPublicId(), memberName(membersById.get(m.getMemberId())),
                        memberCode(membersById.get(m.getMemberId())), m.getPlanName(),
                        m.effectiveStatus(today).name(), m.getStartDate().toString(), m.getEndDate().toString(),
                        m.getNetAmount(), m.getAmountPaid(), balance(m)))
                .toList();

        List<ReportOperations.DueRow> dueRows = outstanding.stream()
                .map(m -> new ReportOperations.DueRow(
                        m.getPublicId(), memberName(membersById.get(m.getMemberId())),
                        memberCode(membersById.get(m.getMemberId())), m.getPlanName(),
                        m.effectiveStatus(today).name(), m.getEndDate().toString(), m.getNetAmount(),
                        m.getAmountPaid(), balance(m)))
                .toList();

        return new ReportOperations(
                overview, collectionsByMethod, paymentRows, expiringRows, dueRows,
                newMemberRows.stream().map(this::memberRow).toList(), attendanceRows, inactiveMembers);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> memberships(Long tenantId) {
        requireTenant(tenantId);
        return membershipRepository.findByTenantIdAndDeletedFalse(tenantId).stream()
                .map(this::membershipRow).toList();
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

    private ReportOperations.MemberRow memberRow(Member member) {
        return new ReportOperations.MemberRow(member.getPublicId(), member.getMemberCode(),
                member.getFullName(), member.getPhone(), member.getJoinedOn().toString());
    }

    private BigDecimal balance(Membership membership) {
        return membership.getNetAmount().subtract(membership.getAmountPaid()).max(BigDecimal.ZERO);
    }

    private String memberName(Member member) {
        return member == null ? "Unknown member" : member.getFullName();
    }

    private String memberCode(Member member) {
        return member == null ? "" : member.getMemberCode();
    }

    private void requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw CommonExceptions.badRequest("A gym tenant is required");
        }
    }
}
