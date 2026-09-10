package com.example.gym.membership;

/** Business lifecycle state of a membership (independent of device authorization state). */
public enum MembershipStatus {
    PENDING,   // created, start date in the future
    ACTIVE,    // currently valid
    FROZEN,    // temporarily suspended (validity paused)
    EXPIRED,   // past end date
    CANCELLED  // terminated early
}
