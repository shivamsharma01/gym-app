package com.example.gym.device.domain;

/** Outbox command lifecycle (§9). */
public enum SyncCommandState {
    PENDING,
    DISPATCHED,
    ACKNOWLEDGED,
    SUCCEEDED,
    RETRYING,
    FAILED,
    DEAD_LETTER,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEAD_LETTER || this == CANCELLED;
    }
}
