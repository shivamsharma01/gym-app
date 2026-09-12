package com.example.gym.settings;

import com.example.gym.security.SecurityUtils;
import com.example.gym.settings.dto.GymSettingsView;
import com.example.gym.settings.dto.UpdateGymSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
@Tag(name = "Settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    @Operation(summary = "Gym profile shown on the public site")
    public GymSettingsView get() {
        return settingsService.view(SecurityUtils.currentTenantId());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    @Operation(summary = "Update gym profile")
    public GymSettingsView update(@Valid @RequestBody UpdateGymSettings request) {
        return settingsService.update(request, SecurityUtils.currentTenantId());
    }
}
