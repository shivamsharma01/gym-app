package com.example.gym.audit;

import com.example.gym.common.web.PageResponse;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit Log")
@PreAuthorize("hasAuthority('AUDIT_VIEW')")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final AuditLogRepository repository;
    private final AuditLabelResolver labelResolver;

    public AuditController(AuditLogRepository repository, AuditLabelResolver labelResolver) {
        this.repository = repository;
        this.labelResolver = labelResolver;
    }

    @GetMapping("/options")
    @Transactional(readOnly = true)
    @Operation(summary = "Actors and actions that appear in this gym's audit log")
    public AuditFilterOptions options() {
        Long tenantId = SecurityUtils.currentTenantId();
        if (tenantId == null) {
            return new AuditFilterOptions(repository.findAllActors(), repository.findAllActions());
        }
        return new AuditFilterOptions(repository.findActors(tenantId), repository.findActions(tenantId));
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "List audit entries for the current tenant (most recent first)")
    public PageResponse<AuditLogResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "actor", required = false) List<String> actor,
            @RequestParam(name = "action", required = false) List<String> action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        Long tenantId = SecurityUtils.currentTenantId();
        List<String> actors = clean(actor);
        List<String> actions = clean(action);
        if (from != null && to != null && from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        Instant start = from == null ? null : from.atStartOfDay(ZONE).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toInstant();
        Page<AuditLog> result = actors.isEmpty() && actions.isEmpty() && start == null && end == null
                ? unfiltered(tenantId, pageable)
                : repository.findAll(filtered(tenantId, actors, actions, start, end), pageable);
        return PageResponse.from(result, log -> AuditLogResponse.from(log,
                labelResolver.resolve(log.getResourceType(), log.getResourceId(), log.getDetails())));
    }

    private Page<AuditLog> unfiltered(Long tenantId, PageRequest pageable) {
        return tenantId == null
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
    }

    private static Specification<AuditLog> filtered(Long tenantId, List<String> actors, List<String> actions,
                                                    Instant start, Instant end) {
        return (root, query, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            if (tenantId != null) {
                parts.add(cb.equal(root.get("tenantId"), tenantId));
            }
            if (!actors.isEmpty()) {
                parts.add(root.get("actorUsername").in(actors));
            }
            if (!actions.isEmpty()) {
                parts.add(root.get("action").in(actions));
            }
            if (start != null) {
                parts.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
            }
            if (end != null) {
                parts.add(cb.lessThan(root.get("createdAt"), end));
            }
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                query.orderBy(cb.desc(root.get("createdAt")));
            }
            return cb.and(parts.toArray(Predicate[]::new));
        };
    }

    private static List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }
}
