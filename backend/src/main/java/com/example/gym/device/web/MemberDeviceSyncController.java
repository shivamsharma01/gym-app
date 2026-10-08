package com.example.gym.device.web;

import com.example.gym.device.MemberDeviceSyncService;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members/{id}/device-sync")
@Tag(name = "Members")
public class MemberDeviceSyncController {

    private final MemberDeviceSyncService syncService;

    public MemberDeviceSyncController(MemberDeviceSyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MEMBER_VIEW')")
    @Operation(summary = "Where this member and their face have reached, per device")
    public MemberDeviceSyncService.SyncStatus status(@PathVariable String id) {
        return syncService.status(id, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{deviceId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('DEVICE_SYNC')")
    @Operation(summary = "Re-send this member's name, access and face to one device")
    public void retry(@PathVariable String id, @PathVariable String deviceId) {
        syncService.retry(id, deviceId, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{deviceId}/remove")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('DEVICE_SYNC')")
    @Operation(summary = "Remove this member from one reader. The member stays, and no other reader is written")
    public void remove(@PathVariable String id, @PathVariable String deviceId) {
        syncService.removeFromReader(id, deviceId, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{deviceId}/read")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('DEVICE_SYNC')")
    @Operation(summary = "Read this member's name, access and face fresh from one device")
    public void read(@PathVariable String id, @PathVariable String deviceId) {
        syncService.readFromDevice(id, deviceId, SecurityUtils.currentTenantId());
    }
}
