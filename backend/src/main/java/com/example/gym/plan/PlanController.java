package com.example.gym.plan;

import com.example.gym.common.web.PageResponse;
import com.example.gym.plan.dto.PlanRequests.CreatePlan;
import com.example.gym.plan.dto.PlanRequests.UpdatePlan;
import com.example.gym.plan.dto.PlanResponse;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "Membership Plans")
public class PlanController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MEMBERSHIP_VIEW')")
    @Operation(summary = "List membership plans")
    public PageResponse<PlanResponse> list(
            @RequestParam(required = false) PlanStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.ASC, "name"));
        return PageResponse.from(
                planService.list(SecurityUtils.currentTenantId(), status, pageable), PlanResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBERSHIP_VIEW')")
    @Operation(summary = "Get a membership plan")
    public PlanResponse get(@PathVariable String id) {
        return PlanResponse.from(planService.getByPublicId(id, SecurityUtils.currentTenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MEMBERSHIP_CREATE')")
    @Operation(summary = "Create a membership plan")
    public PlanResponse create(@Valid @RequestBody CreatePlan request) {
        return PlanResponse.from(planService.create(request, SecurityUtils.currentTenantId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBERSHIP_UPDATE')")
    @Operation(summary = "Update a membership plan")
    public PlanResponse update(@PathVariable String id, @Valid @RequestBody UpdatePlan request) {
        return PlanResponse.from(planService.update(id, request, SecurityUtils.currentTenantId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MEMBERSHIP_UPDATE')")
    @Operation(summary = "Archive a membership plan (soft; preserves history)")
    public void archive(@PathVariable String id) {
        planService.archive(id, SecurityUtils.currentTenantId());
    }
}
