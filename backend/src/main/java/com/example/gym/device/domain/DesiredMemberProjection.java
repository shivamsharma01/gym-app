package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import com.example.gym.device.ExpectedOccupant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The member this reader should hold at one revision. The gateway writes this record and reads it
 * back. The row does not store a public id for the adapter.
 */
@Entity
@Table(name = "desired_member")
public class DesiredMemberProjection extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** Null only for a rejected enrollment: the device user is removed and no member is created. */
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "device_user_id", nullable = false, length = 64)
    private String deviceUserId;

    @Column(name = "present_on_reader", nullable = false)
    private boolean presentOnReader;

    @Column(name = "reader_name", nullable = false, length = 31)
    private String readerName;

    @Column(name = "reader_name_ex", length = 127)
    private String readerNameEx;

    @Column(name = "user_status", nullable = false)
    private int userStatus;

    @Column(name = "valid_from", nullable = false, length = 40)
    private String validFrom;

    @Column(name = "valid_to", nullable = false, length = 40)
    private String validTo;

    @Column(name = "authority", nullable = false, length = 32)
    private String authority;

    @Column(name = "door_num", nullable = false)
    private int doorNum;

    @Column(name = "time_section_num", nullable = false)
    private int timeSectionNum;

    /** False means the user stays on the reader and the face photo does not. */
    @Column(name = "face_present", nullable = false)
    private boolean facePresent = true;

    @Column(name = "face_sha256", nullable = false, length = 64)
    private String faceSha256;

    @Column(name = "observed_face_sha256", length = 64)
    private String observedFaceSha256;

    /**
     * False: the gateway is sent the reader's verified baseline for this id, or no one. True: the
     * columns below are what the reader must still show, set by a new id or a staff decision and
     * kept until the reader verifies a revision of this row.
     */
    @Column(name = "expected_set", nullable = false)
    private boolean expectedSet;

    @Column(name = "expected_present", nullable = false)
    private boolean expectedPresent;

    @Column(name = "expected_name", length = 127)
    private String expectedName;

    @Column(name = "expected_name_ex", length = 127)
    private String expectedNameEx;

    @Column(name = "expected_user_status")
    private Integer expectedUserStatus;

    @Column(name = "expected_valid_from", length = 40)
    private String expectedValidFrom;

    @Column(name = "expected_valid_to", length = 40)
    private String expectedValidTo;

    @Column(name = "expected_authority", length = 32)
    private String expectedAuthority;

    @Column(name = "expected_face_sha256", length = 64)
    private String expectedFaceSha256;

    /** The reader showed something other than the expected record. Not pulled until staff decide. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** The revision the gateway last acknowledged for this row; applied only when it equals {@link #revision}. */
    @Column(name = "acked_revision")
    private Long ackedRevision;

    /** The gateway's last reported failure, for {@link #applyErrorRevision} only. */
    @Column(name = "apply_error", length = 255)
    private String applyError;

    @Column(name = "apply_error_revision")
    private Long applyErrorRevision;

    @Column(name = "apply_attempts")
    private Integer applyAttempts;

    @Column(name = "apply_error_permanent", nullable = false)
    private boolean applyErrorPermanent;

    /** The revision a seed trigger placed this row at; null when staff or a member create placed it. */
    @Column(name = "seed_revision")
    private Long seedRevision;

    protected DesiredMemberProjection() {
    }

    public Long getSeedRevision() {
        return seedRevision;
    }

    public void setSeedRevision(Long seedRevision) {
        this.seedRevision = seedRevision;
    }

    /**
     * A seed the gateway has never been sent, in any version: no pull served its first revision and
     * no read-back confirmed it. {@code served} is the highest revision any pull of this reader served.
     */
    public boolean unservedSeed(long served) {
        return seedRevision != null && ackedRevision == null && seedRevision > served;
    }

    public DesiredMemberProjection(Long tenantId, Long deviceId, Long memberId) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
        this.memberId = memberId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getMemberId() {
        return memberId;
    }

    /** Cleared when the member row is deleted but the reader still has to drop this id. */
    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public String getDeviceUserId() {
        return deviceUserId;
    }

    public void setDeviceUserId(String deviceUserId) {
        this.deviceUserId = deviceUserId;
    }

    public boolean isPresentOnReader() {
        return presentOnReader;
    }

    public void setPresentOnReader(boolean presentOnReader) {
        this.presentOnReader = presentOnReader;
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

    public int getUserStatus() {
        return userStatus;
    }

    public void setUserStatus(int userStatus) {
        this.userStatus = userStatus;
    }

    public String getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(String validFrom) {
        this.validFrom = validFrom;
    }

    public String getValidTo() {
        return validTo;
    }

    public void setValidTo(String validTo) {
        this.validTo = validTo;
    }

    public String getAuthority() {
        return authority;
    }

    public void setAuthority(String authority) {
        this.authority = authority;
    }

    public int getDoorNum() {
        return doorNum;
    }

    public void setDoorNum(int doorNum) {
        this.doorNum = doorNum;
    }

    public int getTimeSectionNum() {
        return timeSectionNum;
    }

    public void setTimeSectionNum(int timeSectionNum) {
        this.timeSectionNum = timeSectionNum;
    }

    public boolean isFacePresent() {
        return facePresent;
    }

    public void setFacePresent(boolean facePresent) {
        this.facePresent = facePresent;
    }

    public String getFaceSha256() {
        return faceSha256;
    }

    public void setFaceSha256(String faceSha256) {
        this.faceSha256 = faceSha256;
    }

    public String getObservedFaceSha256() {
        return observedFaceSha256;
    }

    public void setObservedFaceSha256(String observedFaceSha256) {
        this.observedFaceSha256 = observedFaceSha256;
    }

    /** The stored precondition, or null when the verified baseline is the precondition. */
    public ExpectedOccupant getExpected() {
        if (!expectedSet) {
            return null;
        }
        return new ExpectedOccupant(expectedPresent, expectedName, expectedNameEx,
                expectedUserStatus, expectedValidFrom, expectedValidTo, expectedAuthority, expectedFaceSha256);
    }

    public void setExpected(ExpectedOccupant expected) {
        expectedSet = expected != null;
        expectedPresent = expected != null && expected.present();
        expectedName = expected == null ? null : cut(expected.name(), 127);
        expectedNameEx = expected == null ? null : cut(expected.nameEx(), 127);
        expectedUserStatus = expected == null ? null : expected.userStatus();
        expectedValidFrom = expected == null ? null : cut(expected.validFrom(), 40);
        expectedValidTo = expected == null ? null : cut(expected.validTo(), 40);
        expectedAuthority = expected == null ? null : cut(expected.authority(), 32);
        expectedFaceSha256 = expected == null ? null : cut(expected.faceSha256(), 64);
    }

    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    /** The current revision read back on the reader. */
    public boolean isApplied() {
        return ackedRevision != null && ackedRevision == revision;
    }

    public void acknowledged(long ackedRevision) {
        this.ackedRevision = ackedRevision;
        if (applyErrorRevision != null && applyErrorRevision <= ackedRevision) {
            applyError = null;
            applyErrorRevision = null;
            applyAttempts = null;
            applyErrorPermanent = false;
        }
    }

    public void failed(long failedRevision, String error, int attempts, boolean permanent) {
        applyError = cut(error, 255);
        applyErrorRevision = failedRevision;
        applyAttempts = attempts;
        applyErrorPermanent = permanent;
    }

    /** The failure the gateway reported for the current revision, or null. */
    public String getApplyError() {
        return applyErrorRevision != null && applyErrorRevision == revision ? applyError : null;
    }

    public Integer getApplyAttempts() {
        return getApplyError() == null ? null : applyAttempts;
    }

    public boolean isApplyErrorPermanent() {
        return getApplyError() != null && applyErrorPermanent;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
