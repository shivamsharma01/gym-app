package com.example.gym.enquiry;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.enquiry.dto.CreateEnquiry;
import com.example.gym.enquiry.dto.UpdateEnquiry;
import com.example.gym.tenant.TenantGuard;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnquiryService {

    private final EnquiryRepository enquiryRepository;
    private final AuditService auditService;

    public EnquiryService(EnquiryRepository enquiryRepository, AuditService auditService) {
        this.enquiryRepository = enquiryRepository;
        this.auditService = auditService;
    }

    @Transactional
    public Enquiry create(Long tenantId, CreateEnquiry request) {
        Enquiry enquiry = enquiryRepository.save(new Enquiry(
                tenantId,
                request.name().trim(),
                request.email().trim(),
                blankToNull(request.phone()),
                request.message().trim(),
                blankToNull(request.planInterest())));
        auditService.record(AuditActions.ENQUIRY_RECEIVED, AuditActions.RESULT_SUCCESS,
                "Enquiry", enquiry.getPublicId(), null);
        return enquiry;
    }

    @Transactional(readOnly = true)
    public Page<Enquiry> list(Long tenantId, EnquiryStatus status, Pageable pageable) {
        if (tenantId == null) {
            throw CommonExceptions.badRequest("A gym tenant is required");
        }
        return status == null
                ? enquiryRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                : enquiryRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status, pageable);
    }

    @Transactional
    public Enquiry update(String publicId, UpdateEnquiry request, Long tenantId) {
        Enquiry enquiry = enquiryRepository.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Enquiry"));
        TenantGuard.check(enquiry, tenantId, "Enquiry");
        if (request.status() != null) {
            enquiry.setStatus(request.status());
        }
        if (request.staffNotes() != null) {
            enquiry.setStaffNotes(blankToNull(request.staffNotes()));
        }
        auditService.record(AuditActions.ENQUIRY_UPDATED, AuditActions.RESULT_SUCCESS,
                "Enquiry", enquiry.getPublicId(), null);
        return enquiryRepository.save(enquiry);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
