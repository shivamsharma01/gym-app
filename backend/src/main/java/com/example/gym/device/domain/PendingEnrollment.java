package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A person created on one reader, waiting until staff decide. The device user id is the reader's id.
 */
@Entity
@Table(name = "pending_enrollment")
public class PendingEnrollment extends TenantAwareEntity {

    public static final String PENDING = "PENDING";

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_user_id", nullable = false, length = 64)
    private String deviceUserId;

    @Column(name = "review_status", nullable = false, length = 16)
    private String reviewStatus;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "decision", length = 32)
    private String decision;

    @Column(name = "actor", length = 100)
    private String actor;

    @Column(name = "prior_state", length = 255)
    private String priorState;

    @Column(name = "chosen_state", length = 255)
    private String chosenState;

    @Column(name = "decision_revision")
    private Long decisionRevision;

    @Column(name = "verification_error", length = 255)
    private String verificationError;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    protected PendingEnrollment() {
    }

    public PendingEnrollment(Long tenantId, Long deviceId, String deviceUserId) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
        this.deviceUserId = deviceUserId;
        this.reviewStatus = PENDING;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public String getDeviceUserId() {
        return deviceUserId;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }

    public String getDecision() {
        return decision;
    }

    public String getActor() {
        return actor;
    }

    public String getPriorState() {
        return priorState;
    }

    public String getChosenState() {
        return chosenState;
    }

    public Long getDecisionRevision() {
        return decisionRevision;
    }

    public String getVerificationError() {
        return verificationError;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public void decide(String decision, String actor, String priorState, String chosenState, long revision) {
        this.decision = decision;
        this.actor = actor;
        this.priorState = priorState;
        this.chosenState = chosenState;
        this.decisionRevision = revision;
        this.verificationError = null;
        this.resolved = false;
    }

    public void noteVerificationError(String error) {
        this.verificationError = error;
        this.resolved = false;
    }

    public void resolve() {
        this.resolved = true;
        this.verificationError = null;
        if (decision != null) {
            this.reviewStatus = decision;
        }
    }
}
