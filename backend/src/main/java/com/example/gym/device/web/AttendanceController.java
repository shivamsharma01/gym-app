package com.example.gym.device.web;

import com.example.gym.common.web.PageResponse;
import com.example.gym.device.DeviceReadService;
import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.dto.DeviceResponses.AttendanceView;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    private final MemberRepository memberRepository;

    public AttendanceController(DeviceReadService deviceReadService, MemberRepository memberRepository) {
        this.deviceReadService = deviceReadService;
        this.memberRepository = memberRepository;
    }

    @GetMapping("/attendance")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW')")
    @Operation(summary = "List attendance events by time (direction=desc newest first, the default, or asc)")
    public PageResponse<AttendanceView> list(
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = pageByTime(page, size, direction);
        Page<AttendanceEvent> result = deviceReadService.attendance(SecurityUtils.currentTenantId(), pageable);
        Map<Long, String> names = memberNames(result.getContent());
        return PageResponse.from(result, e -> AttendanceView.from(e, memberName(names, e.getMemberId())));
    }

    @GetMapping("/members/{memberId}/attendance")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW')")
    @Operation(summary = "List a member's attendance history")
    public PageResponse<AttendanceView> forMember(
            @PathVariable String memberId,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = pageByTime(page, size, direction);
        Page<AttendanceEvent> result = deviceReadService.attendanceForMember(
                memberId, SecurityUtils.currentTenantId(), pageable);
        Map<Long, String> names = memberNames(result.getContent());
        return PageResponse.from(result, e -> AttendanceView.from(e, memberName(names, e.getMemberId())));
    }

    /** Events with the same time keep the order they were stored in, so pages never shuffle. */
    private static PageRequest pageByTime(int page, int size, String direction) {
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(page, 0), safeSize, Sort.by(dir, "occurredAt", "id"));
    }

    private Map<Long, String> memberNames(List<AttendanceEvent> events) {
        Set<Long> ids = events.stream()
                .map(AttendanceEvent::getMemberId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Member member : memberRepository.findAllById(ids)) {
                names.put(member.getId(), member.getFullName());
            }
        }
        return names;
    }

    private static String memberName(Map<Long, String> names, Long memberId) {
        return memberId == null ? null : names.get(memberId);
    }
}
