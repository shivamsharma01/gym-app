package com.example.gym.member;

import com.example.gym.common.web.PageResponse;
import com.example.gym.member.dto.MemberRequests.CreateMember;
import com.example.gym.member.dto.MemberRequests.UpdateMember;
import com.example.gym.member.dto.MemberResponse;
import com.example.gym.security.SecurityUtils;
import java.io.IOException;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/members")
@Tag(name = "Members")
public class MemberController {

    private static final int MAX_PAGE_SIZE = 100;
    /** Enough for one gym's member list when looking for disagreeing reader ids. */
    private static final int MAX_DIFFER_SCAN = 10_000;

    private final MemberService memberService;
    private final MemberEraseService memberEraseService;
    private final MemberCoverage memberCoverage;
    private final MemberReaderIds memberReaderIds;
    private final ReaderMemberCreate readerMemberCreate;

    public MemberController(MemberService memberService, MemberEraseService memberEraseService,
                            MemberCoverage memberCoverage, MemberReaderIds memberReaderIds,
                            ReaderMemberCreate readerMemberCreate) {
        this.memberService = memberService;
        this.memberEraseService = memberEraseService;
        this.memberCoverage = memberCoverage;
        this.memberReaderIds = memberReaderIds;
        this.readerMemberCreate = readerMemberCreate;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MEMBER_VIEW')")
    @Operation(summary = "Search/list members by name, phone, or member code. "
            + "sort=name (default newest first), direction=asc|desc. "
            + "idsDiffer=true keeps members whose known reader ids are not all the same.")
    public PageResponse<MemberResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) DeviceAuthority deviceAuthority,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(defaultValue = "false") boolean idsDiffer,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Sort ordering = listSort(sort, direction);
        Long tenantId = SecurityUtils.currentTenantId();
        if (idsDiffer) {
            var matched = memberService.search(tenantId, q, status, deviceAuthority,
                            PageRequest.of(0, MAX_DIFFER_SCAN, ordering))
                    .getContent();
            var known = memberReaderIds.ofAll(matched, tenantId);
            var differing = matched.stream()
                    .filter(member -> MemberReaderIds.idsDiffer(known.getOrDefault(member.getId(), List.of())))
                    .toList();
            int from = Math.min(safePage * safeSize, differing.size());
            int to = Math.min(from + safeSize, differing.size());
            var slice = differing.subList(from, to);
            var coverage = memberCoverage.ofAll(slice);
            return PageResponse.from(
                    new PageImpl<>(slice, PageRequest.of(safePage, safeSize, ordering), differing.size()),
                    member -> MemberResponse.from(
                            member, coverage.get(member.getId()), known.getOrDefault(member.getId(), List.of())));
        }
        PageRequest pageable = PageRequest.of(safePage, safeSize, ordering);
        var members = memberService.search(tenantId, q, status, deviceAuthority, pageable);
        var coverage = memberCoverage.ofAll(members.getContent());
        var readers = memberReaderIds.ofAll(members.getContent(), tenantId);
        return PageResponse.from(members, member -> MemberResponse.from(
                member, coverage.get(member.getId()), readers.getOrDefault(member.getId(), List.of())));
    }

    /** Name sort, or newest first. */
    static Sort listSort(String sort, String direction) {
        Sort.Direction dir = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        if ("name".equalsIgnoreCase(sort)) {
            return Sort.by(new Sort.Order(dir, "firstName"), new Sort.Order(dir, "lastName"), Sort.Order.asc("id"));
        }
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_VIEW')")
    @Operation(summary = "Get a member")
    public MemberResponse get(@PathVariable String id) {
        return respond(memberService.getByPublicId(id, SecurityUtils.currentTenantId()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MEMBER_CREATE')")
    @Operation(summary = "Create a member with a face; every reader of the gym gets a desired user")
    public MemberResponse create(@RequestPart("member") @Valid CreateMember request,
                                 @RequestPart(name = "face", required = false) MultipartFile face)
            throws IOException {
        byte[] raw = face == null ? null : face.getBytes();
        return respond(readerMemberCreate.create(request, SecurityUtils.currentTenantId(), raw));
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

    @DeleteMapping("/{id}/permanent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MEMBER_DELETE')")
    @Operation(summary = "Delete a member from the gym and queue removal on every reader")
    public void erase(@PathVariable String id) {
        memberEraseService.erase(id, SecurityUtils.currentTenantId());
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('MEMBER_DELETE')")
    @Operation(summary = "Reactivate an inactive member account")
    public MemberResponse reactivate(@PathVariable String id) {
        return respond(memberService.reactivate(id, SecurityUtils.currentTenantId()));
    }

    private MemberResponse respond(Member member) {
        return MemberResponse.from(member, memberCoverage.of(member), memberReaderIds.of(member));
    }
}
