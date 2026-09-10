package com.example.gym.security;

import com.example.gym.common.error.CommonExceptions;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Convenience accessors for the current authenticated principal. */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static AppUserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal principal) {
            return principal;
        }
        throw CommonExceptions.unauthorized("No authenticated user");
    }

    /** The caller's tenant id, or {@code null} for platform-level (SUPER_ADMIN) callers. */
    public static Long currentTenantId() {
        return currentPrincipal().getTenantId();
    }

    public static Long currentUserId() {
        return currentPrincipal().getUserId();
    }
}
