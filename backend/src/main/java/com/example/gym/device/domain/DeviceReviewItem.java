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

    @Column(name = "baseline_valid_from", length = 40)
    private String baselineValidFrom;

    @Column(name = "baseline_valid_to", length = 40)
    private String baselineValidTo;

    @Column(name = "server_valid_from", length = 40)
    private String serverValidFrom;

    @Column(name = "server_valid_to", length = 40)
    private String serverValidTo;

    @Column(name = "reader_valid_from", length = 40)
    private String readerValidFrom;

    @Column(name = "reader_valid_to", length = 40)
    private String readerValidTo;

    @Column(name = "baseline_user_status")
    private Integer baselineUserStatus;

    @Column(name = "server_user_status")
    private Integer serverUserStatus;

    @Column(name = "reader_user_status")
    private Integer readerUserStatus;

    @Column(name = "baseline_face_sha256", length = 64)
    private String baselineFaceSha256;

    @Column(name = "server_face_sha256", length = 64)
    private String serverFaceSha256;

    @Column(name = "reader_face_sha256", length = 64)
    private String readerFaceSha256;

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

    /**
     * CONFLICT: the reader shows this member differently. SUSPENDED: the reader shows someone else
     * at the id, so the member was taken off this reader and the id released.
     */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind = CONFLICT;

    public static final String CONFLICT = "CONFLICT";
    public static final String SUSPENDED = "SUSPENDED";

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

    /** A restored member gets a new id; the item follows it so that id's read-back closes it. */
    public void setDeviceUserId(String deviceUserId) {
        this.deviceUserId = deviceUserId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public boolean isSuspended() {
        return SUSPENDED.equals(kind);
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

    public String getBaselineValidFrom() {
        return baselineValidFrom;
    }

    public void setBaselineValidFrom(String baselineValidFrom) {
        this.baselineValidFrom = baselineValidFrom;
    }

    public String getBaselineValidTo() {
        return baselineValidTo;
    }

    public void setBaselineValidTo(String baselineValidTo) {
        this.baselineValidTo = baselineValidTo;
    }

    public String getServerValidFrom() {
        return serverValidFrom;
    }

    public void setServerValidFrom(String serverValidFrom) {
        this.serverValidFrom = serverValidFrom;
    }

    public String getServerValidTo() {
        return serverValidTo;
    }

    public void setServerValidTo(String serverValidTo) {
        this.serverValidTo = serverValidTo;
    }

    public String getReaderValidFrom() {
        return readerValidFrom;
    }

    public void setReaderValidFrom(String readerValidFrom) {
        this.readerValidFrom = readerValidFrom;
    }

    public String getReaderValidTo() {
        return readerValidTo;
    }

    public void setReaderValidTo(String readerValidTo) {
        this.readerValidTo = readerValidTo;
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

    /** A decision that writes nothing to the reader is final when it is made. */
    public void decideWithoutWrite(String decision, String actor, String priorState, String chosenState) {
        this.decision = decision;
        this.actor = actor;
        this.priorState = priorState;
        this.chosenState = chosenState;
        this.decisionRevision = null;
        this.verificationError = null;
        this.resolved = true;
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

    public Integer getBaselineUserStatus() {
        return baselineUserStatus;
    }

    public void setBaselineUserStatus(Integer baselineUserStatus) {
        this.baselineUserStatus = baselineUserStatus;
    }

    public Integer getServerUserStatus() {
        return serverUserStatus;
    }

    public void setServerUserStatus(Integer serverUserStatus) {
        this.serverUserStatus = serverUserStatus;
    }

    public Integer getReaderUserStatus() {
        return readerUserStatus;
    }

    public void setReaderUserStatus(Integer readerUserStatus) {
        this.readerUserStatus = readerUserStatus;
    }

    public String getBaselineFaceSha256() {
        return baselineFaceSha256;
    }

    public void setBaselineFaceSha256(String baselineFaceSha256) {
        this.baselineFaceSha256 = baselineFaceSha256;
    }

    public String getServerFaceSha256() {
        return serverFaceSha256;
    }

    public void setServerFaceSha256(String serverFaceSha256) {
        this.serverFaceSha256 = serverFaceSha256;
    }

    public String getReaderFaceSha256() {
        return readerFaceSha256;
    }

    public void setReaderFaceSha256(String readerFaceSha256) {
        this.readerFaceSha256 = readerFaceSha256;
    }
}
