package com.example.gym.device;

import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.GatewayMessageDedupe;
import com.example.gym.device.repo.GatewayMessageDedupeRepository;
import com.example.gym.device.repo.GatewayRepository;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Persists gateway {@code messageId} values so reconnect/replay cannot double-process the same
 * envelope. Returns false when the id was already seen and has not expired.
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
     * @return true if this messageId is new (or blank / missing — caller proceeds without dedupe)
     */
    @Transactional
    public boolean claim(String messageId, String gatewayPublicId) {
        if (!StringUtils.hasText(messageId)) {
            return true;
        }
        Instant now = Instant.now();
        GatewayMessageDedupe existing = dedupeRepository.findById(messageId).orElse(null);
        if (existing != null) {
            if (existing.getExpiresAt().isAfter(now)) {
                return false;
            }
            dedupeRepository.delete(existing);
        }
        Long gatewayId = null;
        if (StringUtils.hasText(gatewayPublicId)) {
            gatewayId = gatewayRepository.findByPublicId(gatewayPublicId).map(Gateway::getId).orElse(null);
        }
        try {
            dedupeRepository.save(new GatewayMessageDedupe(
                    messageId, gatewayId, now, now.plus(properties.getMessageDedupeTtl())));
            return true;
        } catch (DataIntegrityViolationException race) {
            return false;
        }
    }

    @Transactional
    public int purgeExpired() {
        return dedupeRepository.deleteExpired(Instant.now());
    }
}
