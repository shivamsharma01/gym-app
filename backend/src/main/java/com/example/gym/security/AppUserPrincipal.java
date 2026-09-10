package com.example.gym.security;

import com.example.gym.user.AdminUser;
import com.example.gym.user.UserStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authenticated principal. Authorities are the union of each role's permissions plus a
 * {@code ROLE_<name>} authority per assigned role. Carries the tenant id for request scoping.
 */
public class AppUserPrincipal implements UserDetails {

    private final Long userId;
    private final String publicId;
    private final Long tenantId;
    private final String username;
    private final String passwordHash;
    private final boolean enabled;
    private final boolean accountNonLocked;
    private final Set<GrantedAuthority> authorities;

    public AppUserPrincipal(Long userId, String publicId, Long tenantId, String username,
                            String passwordHash, boolean enabled, boolean accountNonLocked,
                            Set<GrantedAuthority> authorities) {
        this.userId = userId;
        this.publicId = publicId;
        this.tenantId = tenantId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;
        this.authorities = authorities;
    }

    public static AppUserPrincipal from(AdminUser user) {
        Set<GrantedAuthority> auths = new HashSet<>();
        user.getRoles().forEach(role -> {
            auths.add(new SimpleGrantedAuthority("ROLE_" + role.getName()));
            role.getPermissions().forEach(p -> auths.add(new SimpleGrantedAuthority(p.getName())));
        });
        boolean nonLocked = user.getLockedUntil() == null || user.getLockedUntil().isBefore(Instant.now());
        return new AppUserPrincipal(
                user.getId(),
                user.getPublicId(),
                user.getTenantId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getStatus() == UserStatus.ACTIVE,
                nonLocked,
                auths);
    }

    public Long getUserId() {
        return userId;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
