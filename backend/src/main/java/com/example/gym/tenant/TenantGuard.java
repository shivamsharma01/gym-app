package com.example.gym.tenant;

import com.example.gym.common.domain.TenantAwareEntity;
import com.example.gym.common.error.CommonExceptions;

/**
 * Enforces tenant ownership. Fails closed: a tenant-owned resource that belongs to a different
 * tenant is reported as {@code NOT_FOUND} (never leak existence across tenants).
 */
public final class TenantGuard {

    private TenantGuard() {
    }

    /**
     * @param resourceTenantId the owning tenant of the resource being accessed
     * @param currentTenantId  the caller's tenant ({@code null} = platform/SUPER_ADMIN, allowed)
     * @param resourceName     for the not-found message
     */
    public static void check(Long resourceTenantId, Long currentTenantId, String resourceName) {
        if (currentTenantId == null) {
            return; // platform-level access
        }
        if (resourceTenantId == null || !resourceTenantId.equals(currentTenantId)) {
            throw CommonExceptions.notFound(resourceName);
        }
    }

    public static void check(TenantAwareEntity entity, Long currentTenantId, String resourceName) {
        check(entity.getTenantId(), currentTenantId, resourceName);
    }
}
