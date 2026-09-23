package com.example.gym.device;

/**
 * Published after a gateway registers so pending outbox work and reconcile can run outside the
 * registration transaction.
 */
public record GatewayConnectedEvent(String gatewayPublicId, Long gatewayInternalId) {
}
