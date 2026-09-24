package com.example.gym.membership;

/**
 * Published (within the membership transaction) when a membership's access-relevant state changes,
 * so the device package can enqueue the corresponding authorization sync commands as part of the
 * same transaction (transactional outbox). Membership code stays decoupled from device code.
 */
public record MembershipChangedEvent(Long tenantId, Long memberId, Long membershipId, ChangeType type) {

    public enum ChangeType {
        CREATED,
        RENEWED,
        FROZEN,
        UNFROZEN,
        CANCELLED,
        DATES_UPDATED,
        DELETED
    }
}
