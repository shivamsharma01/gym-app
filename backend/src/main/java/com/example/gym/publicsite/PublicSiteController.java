package com.example.gym.publicsite;

import com.example.gym.enquiry.EnquiryService;
import com.example.gym.enquiry.dto.CreateEnquiry;
import com.example.gym.enquiry.dto.EnquiryResponse;
import com.example.gym.plan.MembershipPlanRepository;
import com.example.gym.plan.PlanStatus;
import com.example.gym.plan.dto.PlanResponse;
import com.example.gym.settings.GymProfile;
import com.example.gym.settings.GymProfileRepository;
import com.example.gym.settings.SettingsService;
import com.example.gym.settings.dto.GymSettingsView;
import com.example.gym.tenant.Tenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "Public website")
public class PublicSiteController {

    private final PublicTenantResolver publicTenantResolver;
    private final SettingsService settingsService;
    private final GymProfileRepository profileRepository;
    private final MembershipPlanRepository planRepository;
    private final EnquiryService enquiryService;

    public PublicSiteController(PublicTenantResolver publicTenantResolver,
                                SettingsService settingsService,
                                GymProfileRepository profileRepository,
                                MembershipPlanRepository planRepository,
                                EnquiryService enquiryService) {
        this.publicTenantResolver = publicTenantResolver;
        this.settingsService = settingsService;
        this.profileRepository = profileRepository;
        this.planRepository = planRepository;
        this.enquiryService = enquiryService;
    }

    @GetMapping("/site")
    @Operation(summary = "Public gym profile for the marketing site")
    public GymSettingsView site() {
        Tenant tenant = publicTenantResolver.require();
        GymProfile profile = profileRepository.findByTenantId(tenant.getId()).orElse(null);
        return settingsService.toView(tenant, profile);
    }

    @GetMapping("/plans")
    @Operation(summary = "Active membership plans (public)")
    public List<PlanResponse> plans() {
        Tenant tenant = publicTenantResolver.require();
        return planRepository.findByTenantIdAndStatus(
                        tenant.getId(), PlanStatus.ACTIVE, PageRequest.of(0, 50, Sort.by("name")))
                .map(PlanResponse::from)
                .getContent();
    }

    @PostMapping("/enquiries")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a public enquiry/lead")
    public EnquiryResponse enquire(@Valid @RequestBody CreateEnquiry request) {
        Tenant tenant = publicTenantResolver.require();
        return EnquiryResponse.from(enquiryService.create(tenant.getId(), request));
    }
}
