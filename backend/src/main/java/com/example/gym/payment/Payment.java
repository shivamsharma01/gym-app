package com.example.gym.payment;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A recorded payment (MVP: manual recording, not an online gateway — see §44). Amounts are exact
 * decimals with an ISO-4217 currency. May be linked to a membership; the membership's payment
 * status is recomputed from its COMPLETED payments.
 */
@Entity
@Table(name = "payment")
public class Payment extends TenantAwareEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "membership_id")
    private Long membershipId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "reference", length = 120)
    private String reference;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(name = "received_by_user_id")
    private Long receivedByUserId;

    @Column(name = "received_by_username", length = 100)
    private String receivedByUsername;

    @Column(name = "notes", length = 500)
    private String notes;

    protected Payment() {
    }

    public Payment(Long tenantId, Long memberId, Long membershipId, BigDecimal amount, String currency,
                   PaymentMethod method, PaymentStatus status, LocalDate paidOn) {
        setTenantId(tenantId);
        this.memberId = memberId;
        this.membershipId = membershipId;
        this.amount = amount;
        this.currency = currency;
        this.method = method;
        this.status = status;
        this.paidOn = paidOn;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getMembershipId() {
        return membershipId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public Long getReceivedByUserId() {
        return receivedByUserId;
    }

    public void setReceivedByUserId(Long receivedByUserId) {
        this.receivedByUserId = receivedByUserId;
    }

    public String getReceivedByUsername() {
        return receivedByUsername;
    }

    public void setReceivedByUsername(String receivedByUsername) {
        this.receivedByUsername = receivedByUsername;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
