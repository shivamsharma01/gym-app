package com.example.gym.access;

/** Reason codes explaining an access decision (stable, machine-readable). */
public enum AccessReason {
    ALLOWED,
    /** Active member with no plan: doors use reader / adopted validity dates. */
    READER_VALIDITY,
    MEMBER_INACTIVE,
    NO_MEMBERSHIP,
    NO_ACTIVE_MEMBERSHIP,
    MEMBERSHIP_FROZEN,
    MEMBERSHIP_EXPIRED,
    MEMBERSHIP_CANCELLED
}
