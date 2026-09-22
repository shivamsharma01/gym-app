package com.example.gym.member;

import com.example.gym.common.web.PageResponse;
import com.example.gym.member.dto.MemberRequests.CreateMember;
import com.example.gym.member.dto.MemberRequests.UpdateMember;
import com.example.gym.member.dto.MemberResponse;
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
@RequestMapping("/api/v1/members")
@Tag(name = "Members")
public class MemberController {

    private static final int MAX_PAGE_SIZE = 100;

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
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
        return PageResponse.from(
                memberService.search(SecurityUtils.currentTenantId(), q, status, creationSource, pageable),
                MemberResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_VIEW')")
    @Operation(summary = "Get a member")
    public MemberResponse get(@PathVariable String id) {
        return MemberResponse.from(memberService.getByPublicId(id, SecurityUtils.currentTenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MEMBER_CREATE')")
    @Operation(summary = "Create a member")
    public MemberResponse create(@Valid @RequestBody CreateMember request) {
        return MemberResponse.from(memberService.create(request, SecurityUtils.currentTenantId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_UPDATE')")
    @Operation(summary = "Update a member")
    public MemberResponse update(@PathVariable String id, @Valid @RequestBody UpdateMember request) {
        return MemberResponse.from(memberService.update(id, request, SecurityUtils.currentTenantId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MEMBER_DELETE')")
    @Operation(summary = "Deactivate a member (soft; preserves history)")
    public void deactivate(@PathVariable String id) {
        memberService.deactivate(id, SecurityUtils.currentTenantId());
    }
}
