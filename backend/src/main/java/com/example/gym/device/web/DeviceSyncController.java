package com.example.gym.device.web;

import com.example.gym.common.web.PageResponse;
import com.example.gym.device.DeviceService;
import com.example.gym.device.DeviceSyncService;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.dto.DeviceResponses.SyncCommandView;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/sync-commands")
@Tag(name = "Device Sync (Outbox)")
public class DeviceSyncController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DeviceSyncService deviceSyncService;
    private final DeviceService deviceService;
    private final MemberRepository memberRepository;
    private final JsonMapper jsonMapper;

    public DeviceSyncController(DeviceSyncService deviceSyncService, DeviceService deviceService,
                                MemberRepository memberRepository, JsonMapper jsonMapper) {
        this.deviceSyncService = deviceSyncService;
        this.deviceService = deviceService;
        this.memberRepository = memberRepository;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEVICE_VIEW')")
    @Operation(summary = "List device sync commands (optionally filtered by device / open queue)")
    public PageResponse<SyncCommandView> list(
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "false") boolean openOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long tenantId = SecurityUtils.currentTenantId();
        Long deviceInternalId = deviceId == null ? null
                : deviceService.getByPublicId(deviceId, tenantId).getId();
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        Page<DeviceSyncCommand> result = deviceSyncService.list(tenantId, deviceInternalId, openOnly, pageable);
        Map<Long, String> names = memberNames(result);
        return PageResponse.from(result, c -> SyncCommandView.from(
                c, memberName(names, c.getMemberId()), deviceUserId(c)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DEVICE_VIEW')")
    @Operation(summary = "Get a sync command")
    public SyncCommandView get(@PathVariable String id) {
        DeviceSyncCommand c = deviceSyncService.getByPublicId(id, SecurityUtils.currentTenantId());
        String name = c.getMemberId() == null ? null
                : memberRepository.findById(c.getMemberId()).map(Member::getFullName).orElse(null);
        return SyncCommandView.from(c, name, deviceUserId(c));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('DEVICE_SYNC')")
    @Operation(summary = "Manually retry a failed/dead-lettered command")
    public SyncCommandView retry(@PathVariable String id) {
        DeviceSyncCommand c = deviceSyncService.retry(id, SecurityUtils.currentTenantId());
        String name = c.getMemberId() == null ? null
                : memberRepository.findById(c.getMemberId()).map(Member::getFullName).orElse(null);
        return SyncCommandView.from(c, name, deviceUserId(c));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('DEVICE_SYNC')")
    @Operation(summary = "Cancel a pending command")
    public SyncCommandView cancel(@PathVariable String id) {
        DeviceSyncCommand c = deviceSyncService.cancel(id, SecurityUtils.currentTenantId());
        String name = c.getMemberId() == null ? null
                : memberRepository.findById(c.getMemberId()).map(Member::getFullName).orElse(null);
        return SyncCommandView.from(c, name, deviceUserId(c));
    }

    private Map<Long, String> memberNames(Page<DeviceSyncCommand> page) {
        Set<Long> ids = page.getContent().stream()
                .map(DeviceSyncCommand::getMemberId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Member m : memberRepository.findAllById(ids)) {
                names.put(m.getId(), m.getFullName());
            }
        }
        return names;
    }

    /** Immutable maps reject a null key; commands such as reconcile have no member. */
    private static String memberName(Map<Long, String> names, Long memberId) {
        return memberId == null ? null : names.get(memberId);
    }

    private String deviceUserId(DeviceSyncCommand c) {
        if (c.getPayload() == null || c.getPayload().isBlank()) {
            return null;
        }
        try {
            JsonNode node = jsonMapper.readTree(c.getPayload());
            JsonNode id = node.get("deviceUserId");
            return id == null || id.isNull() ? null : id.asString();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
