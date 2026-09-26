package com.example.gym.audit;

import com.example.gym.common.web.PageResponse;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit Log")
@PreAuthorize("hasAuthority('AUDIT_VIEW')")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository repository;
    private final AuditLabelResolver labelResolver;

    public AuditController(AuditLogRepository repository, AuditLabelResolver labelResolver) {
        this.repository = repository;
        this.labelResolver = labelResolver;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "List audit entries for the current tenant (most recent first)")
    public PageResponse<AuditLogResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        Long tenantId = SecurityUtils.currentTenantId();
        Page<AuditLog> result = tenantId == null
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
        return PageResponse.from(result, log -> AuditLogResponse.from(log,
                labelResolver.resolve(log.getResourceType(), log.getResourceId(), log.getDetails())));
    }
}
