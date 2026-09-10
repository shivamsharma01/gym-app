package com.example.gym.plan;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * A sellable membership plan (e.g. "Monthly", "Annual"). Tenant-scoped. Prices are stored as exact
 * decimals with an ISO-4217 currency; a plan's price/name is snapshotted onto each membership so
 * later plan edits never rewrite historical memberships.
 */
@Entity
@Table(name = "membership_plan")
public class MembershipPlan extends TenantAwareEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Validity length in days applied from a membership's start date. */
    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PlanStatus status = PlanStatus.ACTIVE;

    protected MembershipPlan() {
    }

    public MembershipPlan(Long tenantId, String name, String description, BigDecimal price,
                          String currency, int durationDays) {
        setTenantId(tenantId);
        this.name = name;
        this.description = description;
        this.price = price;
        this.currency = currency;
        this.durationDays = durationDays;
        this.status = PlanStatus.ACTIVE;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(int durationDays) {
        this.durationDays = durationDays;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public void setStatus(PlanStatus status) {
        this.status = status;
    }
}
