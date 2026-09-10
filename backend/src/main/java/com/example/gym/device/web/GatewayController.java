package com.example.gym.device.web;

import com.example.gym.common.web.PageResponse;
import com.example.gym.device.GatewayService;
import com.example.gym.device.dto.DeviceRequests.CreateGateway;
import com.example.gym.device.dto.DeviceResponses.GatewayCreated;
import com.example.gym.device.dto.DeviceResponses.GatewayView;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/gateways")
@Tag(name = "Device Gateways")
public class GatewayController {

    private static final int MAX_PAGE_SIZE = 100;

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEVICE_VIEW')")
    @Operation(summary = "List device gateways")
    public PageResponse<GatewayView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.ASC, "name"));
        return PageResponse.from(
                gatewayService.list(SecurityUtils.currentTenantId(), pageable), GatewayView::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DEVICE_VIEW')")
    @Operation(summary = "Get a gateway")
    public GatewayView get(@PathVariable String id) {
        return GatewayView.from(gatewayService.getByPublicId(id, SecurityUtils.currentTenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Register a gateway (returns its id to configure on the LAN agent)")
    public GatewayCreated create(@Valid @RequestBody CreateGateway request) {
        return gatewayService.create(request.name(), SecurityUtils.currentTenantId());
    }
}
