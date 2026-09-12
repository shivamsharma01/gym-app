package com.example.gym.enquiry;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "enquiry")
public class Enquiry extends TenantAwareEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "email", nullable = false, length = 200)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "message", nullable = false, length = 2000)
    private String message;

    @Column(name = "plan_interest", length = 120)
    private String planInterest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EnquiryStatus status = EnquiryStatus.NEW;

    @Column(name = "staff_notes", length = 1000)
    private String staffNotes;

    protected Enquiry() {
    }

    public Enquiry(Long tenantId, String name, String email, String phone, String message, String planInterest) {
        setTenantId(tenantId);
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.message = message;
        this.planInterest = planInterest;
        this.status = EnquiryStatus.NEW;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getMessage() {
        return message;
    }

    public String getPlanInterest() {
        return planInterest;
    }

    public EnquiryStatus getStatus() {
        return status;
    }

    public void setStatus(EnquiryStatus status) {
        this.status = status;
    }

    public String getStaffNotes() {
        return staffNotes;
    }

    public void setStaffNotes(String staffNotes) {
        this.staffNotes = staffNotes;
    }
}
