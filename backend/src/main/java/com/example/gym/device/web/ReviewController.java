package com.example.gym.device.web;

import com.example.gym.device.BootstrapReportService;
import com.example.gym.device.ReviewDecisionService;
import com.example.gym.device.dto.BootstrapReport;
import com.example.gym.device.dto.LinkMemberRequest;
import com.example.gym.device.dto.ReviewItemView;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reviews")
@Tag(name = "Reviews")
public class ReviewController {

    private final ReviewDecisionService decisions;
    private final BootstrapReportService bootstrap;

    public ReviewController(ReviewDecisionService decisions, BootstrapReportService bootstrap) {
        this.decisions = decisions;
        this.bootstrap = bootstrap;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEVICE_VIEW')")
    @Operation(summary = "Open review items and pending enrollments")
    public List<ReviewItemView> open() {
        return decisions.open(SecurityUtils.currentTenantId());
    }

    @PostMapping("/bootstrap")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Classify the trusted roster into a bootstrap report")
    public BootstrapReport bootstrap() {
        return bootstrap.build(SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/accept-server")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Write the server value to the reader")
    public ReviewItemView acceptServer(@PathVariable String id) {
        return decisions.acceptServer(id, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Restore the server record on the reader")
    public ReviewItemView restore(@PathVariable String id) {
        return decisions.restore(id, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/remove")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Remove the member from this reader")
    public ReviewItemView remove(@PathVariable String id) {
        return decisions.remove(id, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/link")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Link the reader id to an existing member")
    public ReviewItemView link(@PathVariable String id, @Valid @RequestBody LinkMemberRequest request) {
        return decisions.link(id, request.memberId(), SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/create")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Create a member and keep the reader's device user id")
    public ReviewItemView create(@PathVariable String id) {
        return decisions.create(id, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Reject the enrollment and remove that device user")
    public ReviewItemView reject(@PathVariable String id) {
        return decisions.reject(id, SecurityUtils.currentTenantId());
    }
}
