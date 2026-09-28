package com.example.gym.membership;

import com.example.gym.membership.dto.MembershipRequests.CancelMembership;
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

    @DeleteMapping("/memberships/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MEMBERSHIP_DELETE')")
    @Operation(summary = "Delete a membership")
    public void delete(@PathVariable("id") String id) {
        membershipService.delete(id, SecurityUtils.currentTenantId());
    }
}
