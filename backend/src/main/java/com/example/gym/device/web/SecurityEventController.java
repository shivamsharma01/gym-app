package com.example.gym.device.web;

import com.example.gym.common.web.PageResponse;
import com.example.gym.device.DeviceReadService;
import com.example.gym.device.dto.DeviceResponses.SecurityEventView;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/security-events")
@Tag(name = "Security Events")
public class SecurityEventController {

    private static final int MAX_PAGE_SIZE = 200;

    private final DeviceReadService deviceReadService;

    public SecurityEventController(DeviceReadService deviceReadService) {
        this.deviceReadService = deviceReadService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SECURITY_ALERT_VIEW')")
    @Operation(summary = "List security events (denied access, unknown credentials, alarms)")
    public PageResponse<SecurityEventView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        return PageResponse.from(
                deviceReadService.securityEvents(SecurityUtils.currentTenantId(), pageable),
                SecurityEventView::from);
    }
}
