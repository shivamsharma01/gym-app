package com.example.gym.access;

import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
@Tag(name = "Access Status")
public class AccessController {

    private final AccessService accessService;

    public AccessController(AccessService accessService) {
        this.accessService = accessService;
    }

    @GetMapping("/{id}/access")
    @PreAuthorize("hasAuthority('MEMBER_VIEW')")
    @Operation(summary = "Compute a member's current business access decision (allowed/denied + reason)")
    public AccessStatusResponse access(@PathVariable String id) {
        return accessService.decide(id, SecurityUtils.currentTenantId());
    }
}
