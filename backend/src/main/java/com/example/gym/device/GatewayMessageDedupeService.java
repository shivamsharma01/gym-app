package com.example.gym.device;

import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.GatewayMessageDedupe;
import com.example.gym.device.repo.GatewayMessageDedupeRepository;
import com.example.gym.device.repo.GatewayRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Persists the {@code messageId} of successfully processed gateway messages so a reconnect replay
 * of the same envelope is acknowledged without being processed twice.
 */
@Service
public class GatewayMessageDedupeService {

    private final GatewayMessageDedupeRepository dedupeRepository;
    private final GatewayRepository gatewayRepository;
    private final GatewayProperties properties;

    public GatewayMessageDedupeService(GatewayMessageDedupeRepository dedupeRepository,
                                       GatewayRepository gatewayRepository,
                                       GatewayProperties properties) {
        this.dedupeRepository = dedupeRepository;
        this.gatewayRepository = gatewayRepository;
        this.properties = properties;
    }

    /**
     * True when this messageId was already processed successfully (and has not expired). Blank ids
     * are never deduplicated.
     */
    @Transactional(readOnly = true)
    public boolean alreadyProcessed(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return false;
        }
        return dedupeRepository.findById(messageId)
                .map(existing -> existing.getExpiresAt().isAfter(Instant.now()))
                .orElse(false);
    }

    /**
     * Records a message as processed. Called only after processing succeeded, so a message whose
     * processing failed is handled again when the gateway resends it.
     */
    @Transactional
    public void markProcessed(String messageId, String gatewayPublicId) {
        if (!StringUtils.hasText(messageId)) {
            return;
        }
        Instant now = Instant.now();
        Long gatewayId = null;
        if (StringUtils.hasText(gatewayPublicId)) {
            gatewayId = gatewayRepository.findByPublicId(gatewayPublicId).map(Gateway::getId).orElse(null);
        }
        dedupeRepository.save(new GatewayMessageDedupe(
                messageId, gatewayId, now, now.plus(properties.getMessageDedupeTtl())));
    }

    @Transactional
    public int purgeExpired() {
        return dedupeRepository.deleteExpired(Instant.now());
    }
}
