package com.example.gym.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.gym.device.domain.Gateway;
import com.example.gym.device.repo.GatewayRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GatewayAuthServiceTest {

    @Mock
    private GatewayRepository gatewayRepository;

    private GatewayAuthService authService;

    @BeforeEach
    void setUp() {
        authService = new GatewayAuthService(gatewayRepository);
    }

    @Test
    void missingTokenIsRejected() {
        assertThat(authService.authenticate(null)).isEmpty();
        assertThat(authService.authenticate("")).isEmpty();
        assertThat(authService.authenticate("   ")).isEmpty();
        verify(gatewayRepository, never()).findByTokenHash(any());
        verify(gatewayRepository, never()).findByNextTokenHash(any());
    }

    @Test
    void unknownTokenIsRejected() {
        when(gatewayRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        when(gatewayRepository.findByNextTokenHash(any())).thenReturn(Optional.empty());

        assertThat(authService.authenticate("not-a-credential")).isEmpty();
        verify(gatewayRepository, never()).save(any());
    }

    @Test
    void validTokenIdentifiesThatGateway() {
        String token = authService.newToken();
        Gateway gateway = gatewayWith(token, Instant.now().plusSeconds(3600));
        gateway.setPublicId("gw-real");
        when(gatewayRepository.findByTokenHash(authService.hash(token))).thenReturn(Optional.of(gateway));

        assertThat(authService.authenticate(token)).contains(gateway);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = authService.newToken();
        Gateway gateway = gatewayWith(token, Instant.now().minusSeconds(30));
        when(gatewayRepository.findByTokenHash(authService.hash(token))).thenReturn(Optional.of(gateway));

        assertThat(authService.authenticate(token)).isEmpty();
        verify(gatewayRepository, never()).save(any());
    }

    @Test
    void missingExpiryIsRejected() {
        String token = authService.newToken();
        Gateway gateway = gatewayWith(token, null);
        when(gatewayRepository.findByTokenHash(authService.hash(token))).thenReturn(Optional.of(gateway));

        assertThat(authService.authenticate(token)).isEmpty();
    }

    @Test
    void sessionBoundToTheCurrentHashIsAllowedUntilExpiry() {
        String token = authService.newToken();
        Gateway gateway = gatewayWith(token, Instant.now().plusSeconds(60));

        assertThat(authService.sessionAllows(gateway, authService.hash(token))).isTrue();

        gateway.setTokenExpiresAt(Instant.now().minusSeconds(5));
        assertThat(authService.sessionAllows(gateway, authService.hash(token))).isFalse();
        assertThat(authService.sessionAllows(gateway, null)).isFalse();
    }

    @Test
    void sessionBoundToTheOldHashIsRejectedOnceRotationIsIssued() {
        String current = authService.newToken();
        String next = authService.newToken();
        Gateway gateway = gatewayWith(current, Instant.now().plusSeconds(60));
        gateway.setNextTokenHash(authService.hash(next));

        assertThat(authService.sessionAllows(gateway, authService.hash(current))).isFalse();
        assertThat(authService.sessionAllows(gateway, authService.hash(next))).isTrue();
    }

    @Test
    void expiredPendingCredentialIsNotPromoted() {
        String next = authService.newToken();
        Gateway gateway = new Gateway(1L, "Front", "current-hash");
        gateway.setNextTokenHash(authService.hash(next));
        gateway.setTokenExpiresAt(Instant.now().minusSeconds(30));
        when(gatewayRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        when(gatewayRepository.findByNextTokenHash(authService.hash(next))).thenReturn(Optional.of(gateway));

        assertThat(authService.authenticate(next)).isEmpty();
        assertThat(gateway.getTokenHash()).isEqualTo("current-hash");
        assertThat(gateway.getNextTokenHash()).isEqualTo(authService.hash(next));
        verify(gatewayRepository, never()).save(any());
    }

    private Gateway gatewayWith(String token, Instant expiresAt) {
        Gateway gateway = new Gateway(1L, "Front", authService.hash(token));
        gateway.setTokenExpiresAt(expiresAt);
        return gateway;
    }
}
