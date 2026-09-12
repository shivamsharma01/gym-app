package com.example.gym.enquiry;

import com.example.gym.common.web.PageResponse;
import com.example.gym.enquiry.dto.EnquiryResponse;
import com.example.gym.enquiry.dto.UpdateEnquiry;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enquiries")
@Tag(name = "Enquiries")
public class EnquiryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final EnquiryService enquiryService;

    public EnquiryController(EnquiryService enquiryService) {
        this.enquiryService = enquiryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ENQUIRY_VIEW')")
    @Operation(summary = "List enquiries for the current gym")
    public PageResponse<EnquiryResponse> list(
            @RequestParam(required = false) EnquiryStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.from(
                enquiryService.list(SecurityUtils.currentTenantId(), status,
                        PageRequest.of(Math.max(page, 0), safeSize)),
                EnquiryResponse::from);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ENQUIRY_MANAGE')")
    @Operation(summary = "Update enquiry status or staff notes")
    public EnquiryResponse update(@PathVariable String id, @Valid @RequestBody UpdateEnquiry request) {
        return EnquiryResponse.from(enquiryService.update(id, request, SecurityUtils.currentTenantId()));
    }
}
