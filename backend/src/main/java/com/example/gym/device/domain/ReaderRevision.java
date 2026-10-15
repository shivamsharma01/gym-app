package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Monotonic desired revision and the last revision this reader acknowledged. */
@Entity
@Table(name = "reader_revision")
public class ReaderRevision extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "desired_revision", nullable = false)
    private long desiredRevision;

    @Column(name = "applied_revision", nullable = false)
    private long appliedRevision;

    @Column(name = "observation_epoch", length = 64)
    private String observationEpoch;

    @Column(name = "observation_list_seq", nullable = false)
    private long observationListSeq;

    @Column(name = "retired_observation_epoch", length = 64)
    private String retiredObservationEpoch;

    @Column(name = "served_revision", nullable = false)
    private long servedRevision;

    protected ReaderRevision() {
    }

    public ReaderRevision(Long tenantId, Long deviceId) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public long getDesiredRevision() {
        return desiredRevision;
    }

    public long getAppliedRevision() {
        return appliedRevision;
    }

    public long bumpDesired() {
        desiredRevision++;
        return desiredRevision;
    }

    public void setAppliedRevision(long appliedRevision) {
        this.appliedRevision = appliedRevision;
    }

    /** The gateway reader journal whose scans this reader accepts. */
    public String getObservationEpoch() {
        return observationEpoch;
    }

    /** The scan of the newest complete list processed from that journal. */
    public long getObservationListSeq() {
        return observationListSeq;
    }

    /** The journal this reader accepted before the current one; its scans are refused. */
    public String getRetiredObservationEpoch() {
        return retiredObservationEpoch;
    }

    /** Scans of {@code epoch} are accepted from now on; the journal before it is retired. */
    public void adoptObservationEpoch(String epoch) {
        if (observationEpoch != null && !observationEpoch.equals(epoch)) {
            retiredObservationEpoch = observationEpoch;
        }
        observationEpoch = epoch;
        observationListSeq = 0;
    }

    public void listProcessed(long seq) {
        observationListSeq = Math.max(observationListSeq, seq);
    }

    /** The highest revision a pull of this reader has served. */
    public long getServedRevision() {
        return servedRevision;
    }

    public void served(long revision) {
        servedRevision = Math.max(servedRevision, revision);
    }
}
