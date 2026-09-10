package com.example.gym.user;

import com.example.gym.common.web.PageResponse;
import com.example.gym.security.SecurityUtils;
import com.example.gym.user.dto.AssignRolesRequest;
import com.example.gym.user.dto.CreateUserRequest;
import com.example.gym.user.dto.UpdateUserRequest;
import com.example.gym.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
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
@RequestMapping("/api/v1/users")
@Tag(name = "Admin Users")
@PreAuthorize("hasAuthority('USER_MANAGE')")
public class UserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List admin users in the current tenant")
    public PageResponse<UserResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AdminUser> result = userService.list(SecurityUtils.currentTenantId(), pageable);
        return PageResponse.from(result, UserResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single admin user")
    public UserResponse get(@PathVariable String id) {
        return UserResponse.from(userService.getByPublicId(id, SecurityUtils.currentTenantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an admin user")
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return UserResponse.from(userService.create(request, SecurityUtils.currentTenantId()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an admin user's profile")
    public UserResponse update(@PathVariable String id, @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.from(userService.update(id, request, SecurityUtils.currentTenantId()));
    }

    @PutMapping("/{id}/roles")
    @Operation(summary = "Replace an admin user's roles")
    public UserResponse assignRoles(@PathVariable String id,
                                    @Valid @RequestBody AssignRolesRequest request) {
        return UserResponse.from(
                userService.assignRoles(id, request.roles(), SecurityUtils.currentTenantId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Disable an admin user and revoke their sessions")
    public void disable(@PathVariable String id) {
        userService.disable(id, SecurityUtils.currentTenantId());
    }
}
