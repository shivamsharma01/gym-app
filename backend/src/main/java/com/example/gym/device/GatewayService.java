package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.dto.DeviceResponses.GatewayCreated;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.GatewayStatus;
import com.example.gym.device.repo.GatewayRepository;
import com.example.gym.tenant.TenantGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GatewayService {

    private final GatewayRepository gatewayRepository;
    private final GatewayAuthService authService;
    private final AuditService auditService;

    public GatewayService(GatewayRepository gatewayRepository, GatewayAuthService authService,
                          AuditService auditService) {
        this.gatewayRepository = gatewayRepository;
        this.authService = authService;
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

    @Transactional
    public GatewayCreated create(String name, Long tenantId) {
        String token = authService.newToken();
        Gateway saved = gatewayRepository.save(new Gateway(tenantId, name, authService.hash(token)));
        auditService.record(AuditActions.GATEWAY_REGISTERED, AuditActions.RESULT_SUCCESS,
                "Gateway", saved.getPublicId(), Map.of("name", name));
        return GatewayCreated.from(saved, token);
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
