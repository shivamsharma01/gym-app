package com.example.gym.access;

/** Reason codes explaining an access decision (stable, machine-readable). */
public enum AccessReason {
    ALLOWED,
    /** Active member with no plan: doors use reader / adopted validity dates. */
    READER_VALIDITY,
    /** Reader dates are stored and today is after the end date. */
    READER_VALIDITY_EXPIRED,
    /** Reader dates are stored and today is before the start date. */
    READER_VALIDITY_NOT_STARTED,
    /**
     * No plan covers today, but reader / adopted validity still allows access (same as doors).
     * Staff UI should not show this as Denied.
     */
    READER_VALIDITY_NO_ACTIVE_PLAN,
    MEMBER_INACTIVE,
    NO_MEMBERSHIP,
    NO_ACTIVE_MEMBERSHIP,
    MEMBERSHIP_FROZEN,
    MEMBERSHIP_EXPIRED,
    MEMBERSHIP_CANCELLED
}
