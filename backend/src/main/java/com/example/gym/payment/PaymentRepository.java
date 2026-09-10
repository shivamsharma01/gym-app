package com.example.gym.payment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPublicId(String publicId);

    List<Payment> findByMemberIdOrderByPaidOnDescIdDesc(Long memberId);

    List<Payment> findByMembershipIdAndStatus(Long membershipId, PaymentStatus status);

    Page<Payment> findByTenantIdOrderByPaidOnDescIdDesc(Long tenantId, Pageable pageable);
}
