package com.example.gym.settings;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.settings.dto.GymSettingsView;
import com.example.gym.settings.dto.UpdateGymSettings;
import com.example.gym.tenant.Tenant;
import com.example.gym.tenant.TenantRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {

    private final TenantRepository tenantRepository;
    private final GymProfileRepository profileRepository;
    private final AuditService auditService;

    public SettingsService(TenantRepository tenantRepository, GymProfileRepository profileRepository,
                           AuditService auditService) {
        this.tenantRepository = tenantRepository;
        this.profileRepository = profileRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public GymSettingsView view(Long tenantId) {
        Tenant tenant = requireTenant(tenantId);
        GymProfile profile = profileRepository.findByTenantId(tenant.getId()).orElse(null);
        return toView(tenant, profile);
    }

    @Transactional
    public GymSettingsView update(UpdateGymSettings request, Long tenantId) {
        Tenant tenant = requireTenant(tenantId);
        tenant.setName(request.name());
        tenantRepository.save(tenant);
        GymProfile profile = profileRepository.findByTenantId(tenant.getId())
                .orElseGet(() -> new GymProfile(tenant.getId()));
        profile.setTagline(blankToNull(request.tagline()));
        profile.setAbout(blankToNull(request.about()));
        profile.setPhone(blankToNull(request.phone()));
        profile.setEmail(blankToNull(request.email()));
        profile.setAddress(blankToNull(request.address()));
        profile.setHours(blankToNull(request.hours()));
        profileRepository.save(profile);
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.RESULT_SUCCESS,
                "GymProfile", profile.getPublicId(), Map.of("name", tenant.getName()));
        return toView(tenant, profile);
    }

    public GymSettingsView toView(Tenant tenant, GymProfile profile) {
        return new GymSettingsView(
                tenant.getPublicId(),
                tenant.getName(),
                tenant.getSlug(),
                profile == null ? null : profile.getTagline(),
                profile == null ? null : profile.getAbout(),
                profile == null ? null : profile.getPhone(),
                profile == null ? null : profile.getEmail(),
                profile == null ? null : profile.getAddress(),
                profile == null ? null : profile.getHours());
    }

    private Tenant requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw CommonExceptions.badRequest("A gym tenant is required");
        }
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> CommonExceptions.notFound("Gym"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
