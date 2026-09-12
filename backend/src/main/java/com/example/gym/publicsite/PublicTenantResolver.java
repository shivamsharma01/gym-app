package com.example.gym.publicsite;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.tenant.Tenant;
import com.example.gym.tenant.TenantRepository;
import com.example.gym.tenant.TenantStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves which gym the unauthenticated website belongs to.
 * Order: {@code X-Gym-Slug} header → {@code slug} query → subdomain of
 * {@code app.public.base-domain} → optional {@code app.public.tenant-slug} fallback.
 * No gym is assumed to exist until SUPER_ADMIN enrolls one.
 */
@Component
public class PublicTenantResolver {

    public static final String SLUG_HEADER = "X-Gym-Slug";

    private final TenantRepository tenantRepository;
    private final String fallbackSlug;
    private final String baseDomain;

    public PublicTenantResolver(TenantRepository tenantRepository,
                                @Value("${app.public.tenant-slug:}") String fallbackSlug,
                                @Value("${app.public.base-domain:}") String baseDomain) {
        this.tenantRepository = tenantRepository;
        this.fallbackSlug = fallbackSlug == null ? "" : fallbackSlug.trim();
        this.baseDomain = baseDomain == null ? "" : baseDomain.trim().toLowerCase();
    }

    public Tenant require() {
        return require(currentRequest());
    }

    public Tenant require(HttpServletRequest request) {
        String slug = resolveSlug(request);
        if (slug == null || slug.isBlank()) {
            throw CommonExceptions.notFound("Gym");
        }
        return tenantRepository.findBySlug(slug)
                .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
                .orElseThrow(() -> CommonExceptions.notFound("Gym"));
    }

    public String resolveSlug(HttpServletRequest request) {
        if (request != null) {
            String header = request.getHeader(SLUG_HEADER);
            if (header != null && !header.isBlank()) {
                return normalizeSlug(header);
            }
            String query = request.getParameter("slug");
            if (query != null && !query.isBlank()) {
                return normalizeSlug(query);
            }
            String fromHost = slugFromHost(request.getServerName());
            if (fromHost != null) {
                return fromHost;
            }
        }
        if (fallbackSlug.isBlank()) {
            return null;
        }
        return normalizeSlug(fallbackSlug);
    }

    private String slugFromHost(String host) {
        if (host == null || host.isBlank() || baseDomain.isEmpty()) {
            return null;
        }
        String h = host.toLowerCase();
        if (h.equals(baseDomain) || h.equals("www." + baseDomain)) {
            return null;
        }
        String suffix = "." + baseDomain;
        if (!h.endsWith(suffix)) {
            return null;
        }
        String sub = h.substring(0, h.length() - suffix.length());
        if (sub.isEmpty() || sub.contains(".") || "www".equals(sub) || "api".equals(sub) || "app".equals(sub)) {
            return null;
        }
        return normalizeSlug(sub);
    }

    private static String normalizeSlug(String raw) {
        return raw.trim().toLowerCase();
    }

    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest();
        }
        return null;
    }
}
