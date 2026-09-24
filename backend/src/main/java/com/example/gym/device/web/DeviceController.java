package com.example.gym.device.web;

import com.example.gym.common.web.PageResponse;
import com.example.gym.device.DeviceReadService;
import com.example.gym.device.DeviceService;
import com.example.gym.device.DeviceUserImportService;
import com.example.gym.device.ReconciliationConflictService;
import com.example.gym.device.dto.DeviceRequests.CreateDevice;
import com.example.gym.device.dto.DeviceRequests.CreateMapping;
import com.example.gym.device.dto.DeviceRequests.RemoteDoor;
import com.example.gym.device.dto.DeviceRequests.UpdateDevice;
import com.example.gym.device.dto.DeviceResponses.ConflictView;
import com.example.gym.device.dto.DeviceResponses.DeviceHealth;
import com.example.gym.device.dto.DeviceResponses.DeviceView;
import com.example.gym.device.dto.DeviceResponses.ImportUsersResult;
import com.example.gym.device.dto.DeviceResponses.MappingView;
import com.example.gym.device.dto.DeviceResponses.SyncCommandView;
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
@RequestMapping("/api/v1/devices")
@Tag(name = "Devices")
public class DeviceController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DeviceService deviceService;
    private final DeviceReadService deviceReadService;
    private final ReconciliationConflictService conflictService;
    private final DeviceUserImportService importService;

    public DeviceController(DeviceService deviceService,
                            DeviceReadService deviceReadService,
                            ReconciliationConflictService conflictService,
                            DeviceUserImportService importService) {
        this.deviceService = deviceService;
        this.deviceReadService = deviceReadService;
        this.conflictService = conflictService;
        this.importService = importService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEVICE_VIEW')")
    @Operation(summary = "List devices")
    public PageResponse<DeviceView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.ASC, "name"));
        return PageResponse.from(
                deviceService.list(SecurityUtils.currentTenantId(), pageable), DeviceView::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DEVICE_VIEW')")
    @Operation(summary = "Get a device")
    public DeviceView get(@PathVariable String id) {
        return DeviceView.from(deviceService.getByPublicId(id, SecurityUtils.currentTenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Register a device")
    public DeviceView create(@Valid @RequestBody CreateDevice request) {
        return DeviceView.from(deviceService.create(request, SecurityUtils.currentTenantId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Update a device")
    public DeviceView update(@PathVariable String id, @Valid @RequestBody UpdateDevice request) {
        return DeviceView.from(deviceService.update(id, request, SecurityUtils.currentTenantId()));
    }

    @GetMapping("/{id}/health")
    @PreAuthorize("hasAuthority('DEVICE_VIEW')")
    @Operation(summary = "Device/gateway connectivity and sync health (not a simplistic online/offline)")
    public DeviceHealth health(@PathVariable String id) {
        return deviceReadService.health(id, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Map a member to this device (enrolment intent; seeds authorization sync)")
    public MappingView createMapping(@PathVariable String id, @Valid @RequestBody CreateMapping request) {
        return MappingView.from(deviceService.createMapping(
                id, request.memberId(), request.deviceUserId(), SecurityUtils.currentTenantId()));
    }

    @DeleteMapping("/{id}/mappings/{mappingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Unmap a member and enqueue REMOVE_USER on the device")
    public void deleteMapping(@PathVariable String id, @PathVariable String mappingId) {
        deviceService.deleteMapping(id, mappingId, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/reconcile")
    @PreAuthorize("hasAuthority('DEVICE_SYNC')")
    @Operation(summary = "Request attendance + user reconciliation for a device")
    public SyncCommandView reconcile(@PathVariable String id) {
        return SyncCommandView.from(deviceService.reconcile(id, SecurityUtils.currentTenantId()));
    }

    @PostMapping("/{id}/sync-now")
    @PreAuthorize("hasAuthority('DEVICE_SYNC')")
    @Operation(summary = "Admin Sync Now — attendance history + user/membership reconcile")
    public SyncCommandView syncNow(@PathVariable String id) {
        return SyncCommandView.from(deviceService.syncNow(id, SecurityUtils.currentTenantId()));
    }

    @PostMapping("/{id}/import-users")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    @Operation(summary = "Import device users into Members (idempotent; Unknown plan; no face upsert)")
    public ImportUsersResult importUsers(@PathVariable String id) {
        return ImportUsersResult.from(importService.importUsers(id, SecurityUtils.currentTenantId()));
    }

    @GetMapping("/{id}/conflicts")
    @PreAuthorize("hasAuthority('DEVICE_VIEW')")
    @Operation(summary = "Open reconciliation conflicts for a device")
    public PageResponse<ConflictView> conflicts(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.from(
                deviceReadService.conflicts(id, SecurityUtils.currentTenantId(),
                        PageRequest.of(Math.max(page, 0), safeSize)),
                ConflictView::from);
    }

    @PostMapping("/{id}/conflicts/{conflictId}/resolve")
    @PreAuthorize("hasAuthority('DEVICE_SYNC')")
    @Operation(summary = "Resolve a conflict (REMOVE_USER for extras, or dismiss)")
    public ConflictView resolveConflict(
            @PathVariable String id,
            @PathVariable String conflictId,
            @RequestParam(defaultValue = "REMOVE") String action) {
        return ConflictView.from(conflictService.resolve(
                id, conflictId, action, SecurityUtils.currentTenantId()));
    }

    @PostMapping("/{id}/door")
    @PreAuthorize("hasAuthority('DEVICE_REMOTE_CONTROL')")
    @Operation(summary = "Remote door control (high-risk; requires confirmed=true + reason; audited)")
    public SyncCommandView door(@PathVariable String id, @Valid @RequestBody RemoteDoor request) {
        return SyncCommandView.from(deviceService.remoteDoor(
                id, request.action(), request.confirmed(), request.reason(),
                SecurityUtils.currentTenantId()));
    }
}
