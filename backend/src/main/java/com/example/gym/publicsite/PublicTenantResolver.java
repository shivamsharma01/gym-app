package com.example.gym.publicsite;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.tenant.Tenant;
import com.example.gym.tenant.TenantRepository;
import com.example.gym.tenant.TenantStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the gym shown on the unauthenticated website. This deployment is one gym; the slug is
 * configured, not guessed from the browser origin.
 */
@Component
public class PublicTenantResolver {

    private final TenantRepository tenantRepository;
    private final String slug;

    public PublicTenantResolver(TenantRepository tenantRepository,
                                @Value("${app.public.tenant-slug:downtown-fitness}") String slug) {
        this.tenantRepository = tenantRepository;
        this.slug = slug;
    }

    public Tenant require() {
        return tenantRepository.findBySlug(slug)
                .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
                .or(() -> tenantRepository.findAll().stream()
                        .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
                        .findFirst())
                .orElseThrow(() -> CommonExceptions.notFound("Gym"));
    }
}
