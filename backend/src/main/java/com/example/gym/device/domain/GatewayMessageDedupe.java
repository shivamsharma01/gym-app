package com.example.gym.device.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Short-TTL replay cache for gateway messageId so reconnect retries cannot double-process. */
@Entity
@Table(name = "gateway_message_dedupe")
public class GatewayMessageDedupe {

    @Id
    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "gateway_id")
    private Long gatewayId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected GatewayMessageDedupe() {
    }

    public GatewayMessageDedupe(String messageId, Long gatewayId, Instant receivedAt, Instant expiresAt) {
        this.messageId = messageId;
        this.gatewayId = gatewayId;
        this.receivedAt = receivedAt;
        this.expiresAt = expiresAt;
    }

    public String getMessageId() {
        return messageId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
