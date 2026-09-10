package com.example.gym.device.domain;

/**
 * Biometric enrolment state of a member on a device. Because remote face enrolment is UNVERIFIED on
 * the current hardware (Phase 0 assessment §12), the default path is guided on-device enrolment; we
 * never mark {@code ENROLLED} without device confirmation.
 */
public enum EnrollmentStatus {
    PENDING_ENROLL,
    GUIDED_PENDING,
    ENROLLED,
    FAILED
}
