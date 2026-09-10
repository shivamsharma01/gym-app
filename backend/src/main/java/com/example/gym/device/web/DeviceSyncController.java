package com.example.gym.device.web;

import com.example.gym.common.web.PageResponse;
import com.example.gym.device.DeviceService;
import com.example.gym.device.DeviceSyncService;
import com.example.gym.device.dto.DeviceResponses.SyncCommandView;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sync-commands")
@Tag(name = "Device Sync (Outbox)")
public class DeviceSyncController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DeviceSyncService deviceSyncService;
    private final DeviceService deviceService;

    public DeviceSyncController(DeviceSyncService deviceSyncService, DeviceService deviceService) {
        this.deviceSyncService = deviceSyncService;
        this.deviceService = deviceService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEVICE_VIEW')")
    @Operation(summary = "List device sync commands (optionally filtered by device)")
    public PageResponse<SyncCommandView> list(
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long tenantId = SecurityUtils.currentTenantId();
        Long deviceInternalId = deviceId == null ? null
                : deviceService.getByPublicId(deviceId, tenantId).getId();
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        return PageResponse.from(
                deviceSyncService.list(tenantId, deviceInternalId, pageable), SyncCommandView::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DEVICE_VIEW')")
    @Operation(summary = "Get a sync command")
    public SyncCommandView get(@PathVariable String id) {
        return SyncCommandView.from(
                deviceSyncService.getByPublicId(id, SecurityUtils.currentTenantId()));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('DEVICE_SYNC')")
    @Operation(summary = "Manually retry a failed/dead-lettered command")
    public SyncCommandView retry(@PathVariable String id) {
        return SyncCommandView.from(deviceSyncService.retry(id, SecurityUtils.currentTenantId()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('DEVICE_SYNC')")
    @Operation(summary = "Cancel a pending command")
    public SyncCommandView cancel(@PathVariable String id) {
        return SyncCommandView.from(deviceSyncService.cancel(id, SecurityUtils.currentTenantId()));
    }
}
