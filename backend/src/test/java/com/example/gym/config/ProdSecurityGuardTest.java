package com.example.gym.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class ProdSecurityGuardTest {

    @Test
    void rejectsDevDefaultSecret() {
        var guard = new ProdSecurityGuard(
                "dev-only-insecure-secret-change-me-0123456789-0123456789", false);
        assertThrows(IllegalStateException.class,
                () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void rejectsShortSecret() {
        var guard = new ProdSecurityGuard("too-short", false);
        assertThrows(IllegalStateException.class,
                () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void rejectsSimulatorInProd() {
        var guard = new ProdSecurityGuard("x".repeat(48), true);
        assertThrows(IllegalStateException.class,
                () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void acceptsStrongSecret() {
        var guard = new ProdSecurityGuard("x".repeat(48), false);
        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments()));
    }
}
