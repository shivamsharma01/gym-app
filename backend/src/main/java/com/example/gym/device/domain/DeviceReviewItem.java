package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One open difference for a mapped user on one reader. It keeps the server, reader, and baseline
 * snapshots. It does not change the member.
 */
@Entity
@Table(name = "device_review_item")
public class DeviceReviewItem extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "device_user_id", nullable = false, length = 64)
    private String deviceUserId;

    @Column(name = "baseline_name", length = 127)
    private String baselineName;

    @Column(name = "baseline_name_ex", length = 127)
    private String baselineNameEx;

    @Column(name = "baseline_authority", length = 32)
    private String baselineAuthority;

    @Column(name = "server_name", length = 127)
    private String serverName;

    @Column(name = "server_name_ex", length = 127)
    private String serverNameEx;

    @Column(name = "server_authority", length = 32)
    private String serverAuthority;

    @Column(name = "reader_name", length = 127)
    private String readerName;

    @Column(name = "reader_name_ex", length = 127)
    private String readerNameEx;

    @Column(name = "reader_authority", length = 32)
    private String readerAuthority;

    @Column(name = "reader_absent", nullable = false)
    private boolean readerAbsent;

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

    @Column(name = "bootstrap_run_id", length = 36)
    private String bootstrapRunId;

    protected DeviceReviewItem() {
    }

    public DeviceReviewItem(Long tenantId, Long deviceId, Long memberId, String deviceUserId) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
        this.memberId = memberId;
        this.deviceUserId = deviceUserId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getDeviceUserId() {
        return deviceUserId;
    }

    public String getBaselineName() {
        return baselineName;
    }

    public void setBaselineName(String baselineName) {
        this.baselineName = baselineName;
    }

    public String getBaselineNameEx() {
        return baselineNameEx;
    }

    public void setBaselineNameEx(String baselineNameEx) {
        this.baselineNameEx = baselineNameEx;
    }

    public String getBaselineAuthority() {
        return baselineAuthority;
    }

    public void setBaselineAuthority(String baselineAuthority) {
        this.baselineAuthority = baselineAuthority;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerNameEx() {
        return serverNameEx;
    }

    public void setServerNameEx(String serverNameEx) {
        this.serverNameEx = serverNameEx;
    }

    public String getServerAuthority() {
        return serverAuthority;
    }

    public void setServerAuthority(String serverAuthority) {
        this.serverAuthority = serverAuthority;
    }

    public String getReaderName() {
        return readerName;
    }

    public void setReaderName(String readerName) {
        this.readerName = readerName;
    }

    public String getReaderNameEx() {
        return readerNameEx;
    }

    public void setReaderNameEx(String readerNameEx) {
        this.readerNameEx = readerNameEx;
    }

    public String getReaderAuthority() {
        return readerAuthority;
    }

    public void setReaderAuthority(String readerAuthority) {
        this.readerAuthority = readerAuthority;
    }

    public boolean isReaderAbsent() {
        return readerAbsent;
    }

    public void setReaderAbsent(boolean readerAbsent) {
        this.readerAbsent = readerAbsent;
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

    public String getBootstrapRunId() {
        return bootstrapRunId;
    }

    public void assignBootstrapRun(String runId) {
        if (resolved || bootstrapRunId != null || runId == null || runId.isBlank()) {
            return;
        }
        this.bootstrapRunId = runId;
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

    public void clearDecision() {
        this.decision = null;
        this.actor = null;
        this.priorState = null;
        this.chosenState = null;
        this.decisionRevision = null;
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
    }
}
