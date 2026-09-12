package com.example.gym.platform;

import com.example.gym.platform.dto.EnrollTenantRequest;
import com.example.gym.platform.dto.EnrollTenantResponse;
import com.example.gym.platform.dto.TenantSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/tenants")
@Tag(name = "Platform — gyms")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class PlatformTenantController {

    private final PlatformTenantService platformTenantService;

    public PlatformTenantController(PlatformTenantService platformTenantService) {
        this.platformTenantService = platformTenantService;
    }

    @GetMapping
    @Operation(summary = "List enrolled gyms (platform SUPER_ADMIN)")
    public List<TenantSummary> list() {
        return platformTenantService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Enroll a gym: tenant + profile + first GYM_OWNER")
    public EnrollTenantResponse enroll(@Valid @RequestBody EnrollTenantRequest request) {
        return platformTenantService.enroll(request);
    }
}
