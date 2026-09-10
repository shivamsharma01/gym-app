package com.example.gym.device.web;

import com.example.gym.common.web.PageResponse;
import com.example.gym.device.DeviceReadService;
import com.example.gym.device.dto.DeviceResponses.AttendanceView;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Attendance")
public class AttendanceController {

    private static final int MAX_PAGE_SIZE = 200;

    private final DeviceReadService deviceReadService;

    public AttendanceController(DeviceReadService deviceReadService) {
        this.deviceReadService = deviceReadService;
    }

    @GetMapping("/attendance")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW')")
    @Operation(summary = "List attendance events (most recent first)")
    public PageResponse<AttendanceView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        return PageResponse.from(
                deviceReadService.attendance(SecurityUtils.currentTenantId(), pageable),
                AttendanceView::from);
    }

    @GetMapping("/members/{memberId}/attendance")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW')")
    @Operation(summary = "List a member's attendance history")
    public PageResponse<AttendanceView> forMember(
            @PathVariable String memberId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        return PageResponse.from(
                deviceReadService.attendanceForMember(memberId, SecurityUtils.currentTenantId(), pageable),
                AttendanceView::from);
    }
}
