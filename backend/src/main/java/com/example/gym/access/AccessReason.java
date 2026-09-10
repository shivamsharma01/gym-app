package com.example.gym.access;

/** Reason codes explaining an access decision (stable, machine-readable). */
public enum AccessReason {
    ALLOWED,
    MEMBER_INACTIVE,
    NO_MEMBERSHIP,
    NO_ACTIVE_MEMBERSHIP,
    MEMBERSHIP_FROZEN,
    MEMBERSHIP_EXPIRED,
    MEMBERSHIP_CANCELLED,
    PAYMENT_OVERDUE
}
