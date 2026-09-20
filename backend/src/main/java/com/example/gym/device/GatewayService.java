package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.GatewayStatus;
import com.example.gym.device.dto.DeviceResponses.DeviceView;
import com.example.gym.device.dto.DeviceResponses.GatewayCreated;
import com.example.gym.device.dto.GatewayCredentialResponse;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.GatewayRepository;
import com.example.gym.tenant.TenantGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class GatewayService {

    private final GatewayRepository gatewayRepository;
    private final DeviceRepository deviceRepository;
    private final GatewayAuthService authService;
    private final GatewayProperties properties;
    private final AuditService auditService;

    public GatewayService(GatewayRepository gatewayRepository,
                          DeviceRepository deviceRepository,
                          GatewayAuthService authService,
                          GatewayProperties properties,
                          AuditService auditService) {
        this.gatewayRepository = gatewayRepository;
        this.deviceRepository = deviceRepository;
        this.authService = authService;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<Gateway> list(Long tenantId, Pageable pageable) {
        return gatewayRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public Gateway getByPublicId(String publicId, Long tenantId) {
        Gateway gateway = gatewayRepository.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Gateway"));
        TenantGuard.check(gateway.getTenantId(), tenantId, "Gateway");
        return gateway;
    }

    /**
     * Creates a gateway and issues a one-time enrollment token. The operational credential is
     * issued only after {@link #enroll}.
     */
    @Transactional
    public GatewayCreated create(String name, Long tenantId) {
        String enrollment = authService.newToken();
        // Placeholder operational hash until enroll — never equals the enrollment hash.
        String placeholder = authService.hash(authService.newToken());
        Gateway gateway = new Gateway(tenantId, name, placeholder);
        Instant now = Instant.now();
        gateway.setEnrollmentTokenHash(authService.hash(enrollment));
        gateway.setEnrollmentExpiresAt(now.plus(properties.getEnrollmentTtl()));
        gateway.setEnrollmentConsumedAt(null);
        Gateway saved = gatewayRepository.save(gateway);
        auditService.record(AuditActions.GATEWAY_REGISTERED, AuditActions.RESULT_SUCCESS,
                "Gateway", saved.getPublicId(), Map.of("name", name));
        return GatewayCreated.from(saved, enrollment);
    }

    /**
     * Issues a fresh one-time enrollment token so a replacement PC can enroll without creating a
     * new gateway row. Invalidates any previous unused enrollment token.
     */
    @Transactional
    public GatewayCreated reissueEnrollment(String publicId, Long tenantId) {
        Gateway gateway = getByPublicId(publicId, tenantId);
        String enrollment = authService.newToken();
        Instant now = Instant.now();
        gateway.setEnrollmentTokenHash(authService.hash(enrollment));
        gateway.setEnrollmentExpiresAt(now.plus(properties.getEnrollmentTtl()));
        gateway.setEnrollmentConsumedAt(null);
        Gateway saved = gatewayRepository.save(gateway);
        auditService.record(AuditActions.GATEWAY_ENROLLMENT_REISSUED, AuditActions.RESULT_SUCCESS,
                "Gateway", saved.getPublicId(), null);
        return GatewayCreated.from(saved, enrollment);
    }

    /**
     * Exchanges a valid enrollment token for a long-lived operational credential.
     */
    @Transactional
    public GatewayCredentialResponse enroll(String gatewayPublicId, String enrollmentToken) {
        if (!StringUtils.hasText(enrollmentToken)) {
            throw CommonExceptions.unauthorized("Invalid enrollment token");
        }
        Gateway gateway = gatewayRepository.findByPublicId(gatewayPublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Gateway"));

        String enrollmentHash = authService.hash(enrollmentToken);
        if (gateway.getEnrollmentTokenHash() == null
                || !gateway.getEnrollmentTokenHash().equals(enrollmentHash)) {
            throw CommonExceptions.unauthorized("Invalid enrollment token");
        }
        if (gateway.getEnrollmentConsumedAt() != null) {
            throw CommonExceptions.conflict("Enrollment token has already been used");
        }
        Instant now = Instant.now();
        if (gateway.getEnrollmentExpiresAt() == null || now.isAfter(gateway.getEnrollmentExpiresAt())) {
            throw CommonExceptions.unauthorized("Enrollment token has expired");
        }

        String credential = authService.newToken();
        Instant expiresAt = now.plus(properties.getCredentialTtl());
        gateway.setTokenHash(authService.hash(credential));
        gateway.setTokenExpiresAt(expiresAt);
        gateway.setNextTokenHash(null);
        gateway.setEnrollmentConsumedAt(now);
        gatewayRepository.save(gateway);

        auditService.record(AuditActions.GATEWAY_ENROLLED, AuditActions.RESULT_SUCCESS,
                "Gateway", gateway.getPublicId(), null);
        return new GatewayCredentialResponse(gateway.getPublicId(), credential, expiresAt);
    }

    /**
     * Issues a new operational credential while keeping the current one valid until the agent
     * authenticates with the new one (promotes {@code nextTokenHash}).
     */
    @Transactional
    public GatewayCredentialResponse rotate(Gateway gateway) {
        if (gateway == null) {
            throw CommonExceptions.unauthorized("Per-gateway credential required to rotate");
        }
        String credential = authService.newToken();
        Instant expiresAt = Instant.now().plus(properties.getCredentialTtl());
        // Replace any previous pending hash — plaintext of old pending is unrecoverable anyway.
        gateway.setNextTokenHash(authService.hash(credential));
        gateway.setTokenExpiresAt(expiresAt);
        gatewayRepository.save(gateway);

        auditService.record(AuditActions.GATEWAY_CREDENTIAL_ROTATED, AuditActions.RESULT_SUCCESS,
                "Gateway", gateway.getPublicId(), null);
        return new GatewayCredentialResponse(gateway.getPublicId(), credential, expiresAt);
    }

    @Transactional(readOnly = true)
    public List<DeviceView> listDevicesForGateway(Gateway gateway) {
        return deviceRepository.findByGatewayId(gateway.getId()).stream()
                .map(DeviceView::from)
                .toList();
    }

    /** Called by the protocol handler when a gateway connects/registers. */
    @Transactional
    public Gateway markRegistered(String publicId, String agentVersion) {
        Gateway gateway = gatewayRepository.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Gateway"));
        gateway.setStatus(GatewayStatus.ONLINE);
        gateway.setAgentVersion(agentVersion);
        gateway.setLastRegisteredAt(Instant.now());
        gateway.setLastHeartbeatAt(Instant.now());
        return gatewayRepository.save(gateway);
    }

    @Transactional
    public void recordHeartbeat(String publicId) {
        gatewayRepository.findByPublicId(publicId).ifPresent(gateway -> {
            gateway.setStatus(GatewayStatus.ONLINE);
            gateway.setLastHeartbeatAt(Instant.now());
            gatewayRepository.save(gateway);
        });
    }

    @Transactional
    public void markOffline(String publicId) {
        gatewayRepository.findByPublicId(publicId).ifPresent(gateway -> {
            gateway.setStatus(GatewayStatus.OFFLINE);
            gatewayRepository.save(gateway);
        });
    }

    /** Marks gateways OFFLINE when the heartbeat has gone stale (connectivity, not device state). */
    @Transactional
    public int markStaleOffline(Instant cutoff) {
        List<Gateway> stale = gatewayRepository.findByStatusAndLastHeartbeatAtBefore(
                GatewayStatus.ONLINE, cutoff);
        for (Gateway gateway : stale) {
            gateway.setStatus(GatewayStatus.OFFLINE);
            gatewayRepository.save(gateway);
        }
        return stale.size();
    }
}
