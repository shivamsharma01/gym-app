package com.example.gym.membership;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A member's subscription for a period. Plan name/price/currency are snapshotted so later plan
 * edits never rewrite history; renewals create new rows rather than mutating past ones.
 *
 * <p>Business state ({@link MembershipStatus}) and device authorization state
 * ({@link DeviceSyncState}) are tracked independently, per §8 of the spec.
 */
@Entity
@Table(name = "membership")
public class Membership extends TenantAwareEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** Nullable: the originating plan may later be archived/deleted; the snapshot below persists. */
    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "plan_name", nullable = false, length = 120)
    private String planName;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** True when end_date was inferred (e.g. device validity missing → today+1y), not from device. */
    @Column(name = "end_date_inferred", nullable = false)
    private boolean endDateInferred = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MembershipStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 16)
    private MembershipPaymentStatus paymentStatus = MembershipPaymentStatus.UNPAID;

    @Column(name = "amount_paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_sync_state", nullable = false, length = 16)
    private DeviceSyncState deviceSyncState = DeviceSyncState.NOT_SYNCED;

    @Column(name = "frozen_on")
    private LocalDate frozenOn;

    @Column(name = "freeze_days_accumulated", nullable = false)
    private int freezeDaysAccumulated = 0;

    @Column(name = "cancelled_on")
    private LocalDate cancelledOn;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "discount_approved_by_user_id")
    private Long discountApprovedByUserId;

    @Column(name = "discount_approved_by_username", length = 100)
    private String discountApprovedByUsername;

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public Long getDiscountApprovedByUserId() {
        return discountApprovedByUserId;
    }

    public void setDiscountApprovedByUserId(Long discountApprovedByUserId) {
        this.discountApprovedByUserId = discountApprovedByUserId;
    }

    public String getDiscountApprovedByUsername() {
        return discountApprovedByUsername;
    }

    public void setDiscountApprovedByUsername(String discountApprovedByUsername) {
        this.discountApprovedByUsername = discountApprovedByUsername;
    }

    public Instant getDiscountApprovedAt() {
        return discountApprovedAt;
    }

    public void setDiscountApprovedAt(Instant discountApprovedAt) {
        this.discountApprovedAt = discountApprovedAt;
    }

    @Column(name = "discount_approved_at")
    private Instant discountApprovedAt;

    protected Membership() {
    }

    public Membership(Long tenantId, Long memberId, Long planId, String planName, BigDecimal price,
                      String currency, LocalDate startDate, LocalDate endDate, MembershipStatus status) {
        setTenantId(tenantId);
        this.memberId = memberId;
        this.planId = planId;
        this.planName = planName;
        this.price = price;
        this.currency = currency;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
        this.paymentStatus = MembershipPaymentStatus.UNPAID;
        this.amountPaid = BigDecimal.ZERO;
        this.deviceSyncState = DeviceSyncState.NOT_SYNCED;
    }

    /**
     * The effective status as of {@code today}: an {@code ACTIVE} membership whose end date has
     * passed is reported {@code EXPIRED} without needing a scheduled job.
     */
    public MembershipStatus effectiveStatus(LocalDate today) {
        if (status == MembershipStatus.ACTIVE && today.isAfter(endDate)) {
            return MembershipStatus.EXPIRED;
        }
        if (status == MembershipStatus.PENDING && !today.isBefore(startDate)) {
            return today.isAfter(endDate) ? MembershipStatus.EXPIRED : MembershipStatus.ACTIVE;
        }
        return status;
    }

    public boolean coversDate(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getPlanId() {
        return planId;
    }

    public String getPlanName() {
        return planName;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public boolean isEndDateInferred() {
        return endDateInferred;
    }

    public void setEndDateInferred(boolean endDateInferred) {
        this.endDateInferred = endDateInferred;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public void setStatus(MembershipStatus status) {
        this.status = status;
    }

    public MembershipPaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(MembershipPaymentStatus paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public BigDecimal getAmountPaid() {
        return amountPaid;
    }

    public void setAmountPaid(BigDecimal amountPaid) {
        this.amountPaid = amountPaid;
    }

    public DeviceSyncState getDeviceSyncState() {
        return deviceSyncState;
    }

    public void setDeviceSyncState(DeviceSyncState deviceSyncState) {
        this.deviceSyncState = deviceSyncState;
    }

    public LocalDate getFrozenOn() {
        return frozenOn;
    }

    public void setFrozenOn(LocalDate frozenOn) {
        this.frozenOn = frozenOn;
    }

    public int getFreezeDaysAccumulated() {
        return freezeDaysAccumulated;
    }

    public void setFreezeDaysAccumulated(int freezeDaysAccumulated) {
        this.freezeDaysAccumulated = freezeDaysAccumulated;
    }

    public LocalDate getCancelledOn() {
        return cancelledOn;
    }

    public void setCancelledOn(LocalDate cancelledOn) {
        this.cancelledOn = cancelledOn;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public BigDecimal getNetAmount() {
        return price.subtract(discountAmount).max(BigDecimal.ZERO);
    }
}
