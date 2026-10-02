package com.example.gym.member;

import com.example.gym.common.web.PageResponse;
import com.example.gym.member.dto.MemberRequests.CreateMember;
import com.example.gym.member.dto.MemberRequests.UpdateMember;
import com.example.gym.member.dto.MemberResponse;
import com.example.gym.security.SecurityUtils;
import java.util.Map;
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
@RequestMapping("/api/v1/members")
@Tag(name = "Members")
public class MemberController {

    private static final int MAX_PAGE_SIZE = 100;

    private final MemberService memberService;
    private final MemberCoverage memberCoverage;

    public MemberController(MemberService memberService, MemberCoverage memberCoverage) {
        this.memberService = memberService;
        this.memberCoverage = memberCoverage;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MEMBER_VIEW')")
    @Operation(summary = "Search/list members (by name, phone, member code; optional status filter)")
    public PageResponse<MemberResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) MemberCreationSource creationSource,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        var members = memberService.search(SecurityUtils.currentTenantId(), q, status, creationSource, pageable);
        Map<Long, String> coverage = memberCoverage.ofAll(members.getContent());
        return PageResponse.from(members, member -> MemberResponse.from(member, coverage.get(member.getId())));
    }

    @GetMapping("/next-serial")
    @PreAuthorize("hasAuthority('MEMBER_CREATE')")
    @Operation(summary = "The next free serial number (smallest number no member or reader uses)")
    public Map<String, String> nextSerial() {
        return Map.of("serialNumber", memberService.nextSerial(SecurityUtils.currentTenantId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_VIEW')")
    @Operation(summary = "Get a member")
    public MemberResponse get(@PathVariable String id) {
        return respond(memberService.getByPublicId(id, SecurityUtils.currentTenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MEMBER_CREATE')")
    @Operation(summary = "Create a member")
    public MemberResponse create(@Valid @RequestBody CreateMember request) {
        return respond(memberService.create(request, SecurityUtils.currentTenantId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_UPDATE')")
    @Operation(summary = "Update a member")
    public MemberResponse update(@PathVariable String id, @Valid @RequestBody UpdateMember request) {
        return respond(memberService.update(id, request, SecurityUtils.currentTenantId()));
    }

    @PutMapping("/{id}/authority")
    @PreAuthorize("hasAuthority('ROLE_GYM_ADMIN') or hasAuthority('USER_MANAGE')")
    @Operation(summary = "Update a member's terminal authority (USER or ADMIN)")
    public MemberResponse updateAuthority(
            @PathVariable String id,
            @Valid @RequestBody com.example.gym.member.dto.MemberRequests.UpdateDeviceAuthority request) {
        DeviceAuthority auth = DeviceAuthority.fromString(request.authority());
        return respond(memberService.updateAuthority(id, auth, SecurityUtils.currentTenantId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MEMBER_DELETE')")
    @Operation(summary = "Deactivate a member (soft; preserves history)")
    public void deactivate(@PathVariable String id) {
        memberService.deactivate(id, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('MEMBER_DELETE')")
    @Operation(summary = "Reactivate an inactive member account")
    public MemberResponse reactivate(@PathVariable String id) {
        return respond(memberService.reactivate(id, SecurityUtils.currentTenantId()));
    }

    private MemberResponse respond(Member member) {
        return MemberResponse.from(member, memberCoverage.of(member));
    }
}
