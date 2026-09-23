package com.example.gym.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPublicId(String publicId);

    List<Payment> findByMemberIdOrderByPaidOnDescIdDesc(Long memberId);

    List<Payment> findByMembershipIdAndStatus(Long membershipId, PaymentStatus status);

    Page<Payment> findByTenantIdOrderByPaidOnDescIdDesc(Long tenantId, Pageable pageable);

    Page<Payment> findByTenantIdAndPaidOnBetweenOrderByPaidOnDescIdDesc(
            Long tenantId, LocalDate from, LocalDate to, Pageable pageable);

    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p
            where p.tenantId = :tenantId
              and p.status = com.example.gym.payment.PaymentStatus.COMPLETED
              and p.paidOn >= :from
              and p.paidOn <= :to
            """)
    BigDecimal sumCompletedBetween(@Param("tenantId") Long tenantId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);

    long countByTenantIdAndStatusAndPaidOnBetween(
            Long tenantId, PaymentStatus status, LocalDate from, LocalDate to);
}
