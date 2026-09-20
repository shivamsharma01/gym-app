package com.example.gym.platform;

import com.example.gym.platform.dto.EnrollTenantRequest;
import com.example.gym.platform.dto.EnrollTenantResponse;
import com.example.gym.platform.dto.TenantSummary;
import com.example.gym.user.UserService;
import com.example.gym.user.dto.SetPasswordRequest;
import com.example.gym.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/tenants")
@Tag(name = "Platform — gyms")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class PlatformTenantController {

    private final PlatformTenantService platformTenantService;
    private final UserService userService;

    public PlatformTenantController(PlatformTenantService platformTenantService, UserService userService) {
        this.platformTenantService = platformTenantService;
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List enrolled gyms (platform SUPER_ADMIN)")
    public List<TenantSummary> list() {
        return platformTenantService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Enroll a gym: tenant + profile + first GYM_OWNER")
    public EnrollTenantResponse enroll(@Valid @RequestBody EnrollTenantRequest request) {
        return platformTenantService.enroll(request);
    }

    @GetMapping("/{tenantId}/users")
    @Operation(summary = "List staff accounts for a gym")
    public List<UserResponse> listUsers(@PathVariable String tenantId) {
        return userService.listStaffForTenant(tenantId).stream().map(UserResponse::from).toList();
    }

    @GetMapping("/{tenantId}/users/{username}")
    @Operation(summary = "Get a gym staff account by username")
    public UserResponse getUser(@PathVariable String tenantId, @PathVariable String username) {
        return UserResponse.from(userService.getByTenantAndUsername(tenantId, username));
    }

    @PutMapping("/{tenantId}/users/{username}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set a gym staff password without the old password")
    public void resetPassword(@PathVariable String tenantId, @PathVariable String username,
                              @Valid @RequestBody SetPasswordRequest request) {
        var user = userService.getByTenantAndUsername(tenantId, username);
        userService.resetPassword(user.getPublicId(), request.newPassword(), null);
    }
}
