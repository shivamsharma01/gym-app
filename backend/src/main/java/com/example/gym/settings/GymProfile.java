package com.example.gym.settings;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "gym_profile")
public class GymProfile extends TenantAwareEntity {

    @Column(name = "tagline", length = 200)
    private String tagline;

    @Column(name = "about", length = 4000)
    private String about;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "address", length = 300)
    private String address;

    @Column(name = "hours", length = 300)
    private String hours;

    protected GymProfile() {
    }

    public GymProfile(Long tenantId) {
        setTenantId(tenantId);
    }

    public String getTagline() {
        return tagline;
    }

    public void setTagline(String tagline) {
        this.tagline = tagline;
    }

    public String getAbout() {
        return about;
    }

    public void setAbout(String about) {
        this.about = about;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getHours() {
        return hours;
    }

    public void setHours(String hours) {
        this.hours = hours;
    }
}
