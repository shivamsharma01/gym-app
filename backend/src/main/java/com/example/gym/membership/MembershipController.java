package com.example.gym.membership;

import com.example.gym.common.web.PageResponse;
import com.example.gym.membership.dto.MembershipRequests.CancelMembership;
import com.example.gym.membership.dto.PlanActiveMemberResponse;
import com.example.gym.membership.dto.PlanRosterResponse;
import com.example.gym.membership.dto.MembershipRequests.CreateMembership;
import com.example.gym.membership.dto.MembershipRequests.RenewMembership;
import com.example.gym.membership.dto.MembershipRequests.UpdateMembership;
import com.example.gym.membership.dto.MembershipRequests.UpdateMembershipDates;
import com.example.gym.membership.dto.MembershipResponse;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Memberships")
public class MembershipController {

    private final MembershipService membershipService;

    public MembershipController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @GetMapping("/memberships/plans")
    @PreAuthorize("hasAuthority('MEMBERSHIP_VIEW')")
    @Operation(summary = "Each plan with how many members are active on it, and how many of those end within 7 days")
    public List<PlanRosterResponse> planRoster() {
        return membershipService.planRoster(SecurityUtils.currentTenantId());
    }

    @GetMapping("/memberships/plans/{planId}/members")
    @PreAuthorize("hasAuthority('MEMBERSHIP_VIEW')")
    @Operation(summary = "Active members currently on a plan, soonest end date first")
    public PageResponse<PlanActiveMemberResponse> activeMembers(
            @PathVariable String planId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return membershipService.activeMembers(planId, SecurityUtils.currentTenantId(), Math.max(page, 0), safeSize);
    }

    @GetMapping("/members/{memberId}/memberships")
    @PreAuthorize("hasAuthority('MEMBERSHIP_VIEW')")
    @Operation(summary = "List a member's memberships (most recent first)")
    public List<MembershipResponse> listForMember(@PathVariable("memberId") String memberId) {
        LocalDate today = LocalDate.now();
        return membershipService.listForMember(memberId, SecurityUtils.currentTenantId()).stream()
                .map(m -> MembershipResponse.from(m, today))
                .toList();
    }

    @GetMapping("/memberships/{id}")
    @PreAuthorize("hasAuthority('MEMBERSHIP_VIEW')")
    @Operation(summary = "Get a membership")
    public MembershipResponse get(@PathVariable("id") String id) {
        return MembershipResponse.from(
                membershipService.getByPublicId(id, SecurityUtils.currentTenantId()), LocalDate.now());
    }

    @PostMapping("/memberships")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MEMBERSHIP_CREATE')")
    @Operation(summary = "Create a membership for a member")
    public MembershipResponse create(@Valid @RequestBody CreateMembership request) {
        return MembershipResponse.from(
                membershipService.create(request, SecurityUtils.currentTenantId()), LocalDate.now());
    }

    @PutMapping("/memberships/{id}")
    @PreAuthorize("hasAuthority('MEMBERSHIP_UPDATE')")
    @Operation(summary = "Edit a membership's plan, dates and discount")
    public MembershipResponse update(@PathVariable("id") String id,
                                     @Valid @RequestBody UpdateMembership request) {
        return MembershipResponse.from(
                membershipService.updateMembership(id, request, SecurityUtils.currentTenantId()),
                LocalDate.now());
    }

    @PutMapping("/memberships/{id}/dates")
    @PreAuthorize("hasAuthority('MEMBERSHIP_UPDATE')")
    @Operation(summary = "Change a membership's start and end dates")
    public MembershipResponse updateDates(@PathVariable("id") String id,
                                          @Valid @RequestBody UpdateMembershipDates request) {
        return MembershipResponse.from(
                membershipService.updateDates(id, request, SecurityUtils.currentTenantId()),
                LocalDate.now());
    }

    @PostMapping("/memberships/{id}/renew")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MEMBERSHIP_CREATE')")
    @Operation(summary = "Renew a membership (creates a new period, preserving history)")
    public MembershipResponse renew(@PathVariable("id") String id, @Valid @RequestBody RenewMembership request) {
        return MembershipResponse.from(
                membershipService.renew(id, request, SecurityUtils.currentTenantId()), LocalDate.now());
    }

    @PostMapping("/memberships/{id}/freeze")
    @PreAuthorize("hasAuthority('MEMBERSHIP_FREEZE')")
    @Operation(summary = "Freeze (pause) a membership")
    public MembershipResponse freeze(@PathVariable("id") String id) {
        return MembershipResponse.from(
                membershipService.freeze(id, SecurityUtils.currentTenantId()), LocalDate.now());
    }

    @PostMapping("/memberships/{id}/unfreeze")
    @PreAuthorize("hasAuthority('MEMBERSHIP_FREEZE')")
    @Operation(summary = "Unfreeze a membership (extends validity by the frozen duration)")
    public MembershipResponse unfreeze(@PathVariable("id") String id) {
        return MembershipResponse.from(
                membershipService.unfreeze(id, SecurityUtils.currentTenantId()), LocalDate.now());
    }

    @PostMapping("/memberships/{id}/cancel")
    @PreAuthorize("hasAuthority('MEMBERSHIP_CANCEL')")
    @Operation(summary = "Cancel a membership")
    public MembershipResponse cancel(@PathVariable("id") String id,
                                     @Valid @RequestBody CancelMembership request) {
        return MembershipResponse.from(
                membershipService.cancel(id, request.reason(), SecurityUtils.currentTenantId()),
                LocalDate.now());
    }

}
