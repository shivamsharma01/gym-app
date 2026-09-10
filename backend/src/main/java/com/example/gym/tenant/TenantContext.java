package com.example.gym.tenant;

/**
 * Holds the current request's tenant id (numeric internal id) in a thread-local, populated by the
 * authentication filter from the authenticated principal. A {@code null} value means "no tenant
 * scope" — reserved for platform-level {@code SUPER_ADMIN} operations.
 *
 * <p>This is a convenience for cross-cutting scoping; services must still verify ownership
 * explicitly via {@link TenantGuard} rather than trusting a client-supplied id.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    public static Long get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
