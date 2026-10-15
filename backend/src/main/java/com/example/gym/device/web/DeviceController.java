package com.example.gym.device.web;

import com.example.gym.common.web.PageResponse;
import com.example.gym.device.DeviceReadService;
import com.example.gym.device.DeviceService;
import com.example.gym.device.ReaderImportService;
import com.example.gym.device.ReaderImportService.Imported;
import com.example.gym.device.ReaderLinkMatches.Match;
import com.example.gym.device.ReaderLinkService;
import com.example.gym.device.ReaderLinkService.Linked;
import com.example.gym.device.ReaderReconcileService;
import com.example.gym.device.ReaderReconcileService.Reconciled;
import com.example.gym.device.dto.DeviceRequests.CreateDevice;
import com.example.gym.device.dto.DeviceRequests.RemoteDoor;
import com.example.gym.device.dto.DeviceRequests.UpdateDevice;
import com.example.gym.device.dto.DeviceResponses.DeviceHealth;
import com.example.gym.device.dto.DeviceResponses.DeviceView;
import com.example.gym.device.dto.DeviceResponses.SyncCommandView;
import com.example.gym.device.dto.LinkMatchesRequest;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final ReaderReconcileService reconcileService;
    private final ReaderImportService importService;
    private final ReaderLinkService linkService;

    public DeviceController(DeviceService deviceService,
                            DeviceReadService deviceReadService,
                            ReaderReconcileService reconcileService,
                            ReaderImportService importService,
                            ReaderLinkService linkService) {
        this.deviceService = deviceService;
        this.deviceReadService = deviceReadService;
        this.reconcileService = reconcileService;
        this.importService = importService;
        this.linkService = linkService;
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

    @PostMapping("/{id}/reconcile")
    @PreAuthorize("hasAuthority('DEVICE_SYNC')")
    @Operation(summary = "Write every active member with a face that this reader has no mapping for")
    public Reconciled reconcile(@PathVariable String id) {
        return reconcileService.reconcile(id, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/import-pending")
    @PreAuthorize("hasAuthority('DEVICE_SYNC') and hasAuthority('REVIEW_DECIDE')")
    @Operation(summary = "Create a member for every undecided person this reader showed, keeping the reader's record")
    public Imported importPending(@PathVariable String id) {
        return importService.importPending(id, SecurityUtils.currentTenantId());
    }

    @GetMapping("/{id}/link-matches")
    @PreAuthorize("hasAuthority('DEVICE_SYNC') and hasAuthority('REVIEW_DECIDE')")
    @Operation(summary = "Undecided people on this reader who match exactly one member another reader holds")
    public List<Match> linkMatches(@PathVariable String id) {
        return linkService.preview(id, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/link-matches")
    @PreAuthorize("hasAuthority('DEVICE_SYNC') and hasAuthority('REVIEW_DECIDE')")
    @Operation(summary = "Link the confirmed matches that still match, keeping this reader's ids")
    public Linked linkConfirmed(@PathVariable String id, @Valid @RequestBody LinkMatchesRequest request) {
        return linkService.link(id, request, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/scan")
    @PreAuthorize("hasAuthority('DEVICE_SYNC')")
    @Operation(summary = "Ask the gateway to read this reader now instead of waiting for its next scan")
    public SyncCommandView scan(@PathVariable String id) {
        return SyncCommandView.from(deviceService.scanReader(id, SecurityUtils.currentTenantId()));
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
