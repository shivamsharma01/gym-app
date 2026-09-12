package com.example.gym.enquiry;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnquiryRepository extends JpaRepository<Enquiry, Long> {

    Optional<Enquiry> findByPublicId(String publicId);

    Page<Enquiry> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    Page<Enquiry> findByTenantIdAndStatusOrderByCreatedAtDesc(
            Long tenantId, EnquiryStatus status, Pageable pageable);
}
