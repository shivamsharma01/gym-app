package com.example.gym.security.web;

import com.example.gym.security.SecurityUtils;
import com.example.gym.security.domain.PermissionRepository;
import com.example.gym.security.domain.RoleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Roles & Permissions")
public class RoleController {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    public RoleController(RoleRepository roleRepository, PermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAnyAuthority('ROLE_MANAGE', 'USER_MANAGE')")
    @Transactional(readOnly = true)
    @Operation(summary = "List roles available to the current tenant (system + tenant-defined)")
    public List<RoleResponse> roles() {
        return roleRepository.findByTenantIdIsNullOrTenantId(SecurityUtils.currentTenantId()).stream()
                .sorted(Comparator.comparing(r -> r.getName()))
                .map(RoleResponse::from)
                .toList();
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    @Transactional(readOnly = true)
    @Operation(summary = "List all permissions in the catalogue")
    public List<PermissionResponse> permissions() {
        return permissionRepository.findAll().stream()
                .sorted(Comparator.comparing(p -> p.getName()))
                .map(PermissionResponse::from)
                .toList();
    }
}
