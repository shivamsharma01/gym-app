package com.example.gym.config;

import com.example.gym.user.AdminUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.gym.security.domain.Role;
import com.example.gym.security.domain.RoleRepository;
import org.springframework.boot.DefaultApplicationArguments;

class ProdBootstrapSeederTest {

    @Test
    void skipsWhenPasswordUnset() {
        var users = mock(AdminUserRepository.class);
        var roles = mock(RoleRepository.class);
        var encoder = mock(PasswordEncoder.class);
        var seeder = new ProdBootstrapSeeder(users, roles, encoder, "superadmin", "a@b.c", "Admin", "");
        assertDoesNotThrow(() -> seeder.run(new DefaultApplicationArguments()));
        verify(users, never()).save(any());
    }

    @Test
    void skipsWhenSuperAdminExists() {
        var users = mock(AdminUserRepository.class);
        var roles = mock(RoleRepository.class);
        var encoder = mock(PasswordEncoder.class);
        when(users.existsWithRoleName("SUPER_ADMIN")).thenReturn(true);
        var seeder = new ProdBootstrapSeeder(users, roles, encoder, "superadmin", "a@b.c", "Admin", "LongPassword1");
        assertDoesNotThrow(() -> seeder.run(new DefaultApplicationArguments()));
        verify(users, never()).save(any());
    }

    @Test
    void createsWhenMissing() {
        var users = mock(AdminUserRepository.class);
        var roles = mock(RoleRepository.class);
        var encoder = mock(PasswordEncoder.class);
        when(users.existsWithRoleName("SUPER_ADMIN")).thenReturn(false);
        when(users.existsByUsername(anyString())).thenReturn(false);
        when(users.existsByEmail(anyString())).thenReturn(false);
        when(roles.findByNameAndTenantIdIsNull("SUPER_ADMIN"))
                .thenReturn(java.util.Optional.of(new Role("SUPER_ADMIN", "platform", true, null)));
        when(encoder.encode(anyString())).thenReturn("{bcrypt}hash");
        var seeder = new ProdBootstrapSeeder(users, roles, encoder, "superadmin", "a@b.c", "Admin", "LongPassword1");
        assertDoesNotThrow(() -> seeder.run(new DefaultApplicationArguments()));
        verify(users).save(any());
    }
}
