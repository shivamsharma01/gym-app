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

    @Column(name = "display_name", length = 150)
    private String displayName;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "hero_image_url", length = 500)
    private String heroImageUrl;

    @Column(name = "training_image_url", length = 500)
    private String trainingImageUrl;

    @Column(name = "facilities_image_url", length = 500)
    private String facilitiesImageUrl;

    @Column(name = "section_training_title", length = 120)
    private String sectionTrainingTitle;

    @Column(name = "section_training_body", length = 1000)
    private String sectionTrainingBody;

    @Column(name = "section_facilities_title", length = 120)
    private String sectionFacilitiesTitle;

    @Column(name = "section_facilities_body", length = 1000)
    private String sectionFacilitiesBody;

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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public String getHeroImageUrl() {
        return heroImageUrl;
    }

    public void setHeroImageUrl(String heroImageUrl) {
        this.heroImageUrl = heroImageUrl;
    }

    public String getTrainingImageUrl() {
        return trainingImageUrl;
    }

    public void setTrainingImageUrl(String trainingImageUrl) {
        this.trainingImageUrl = trainingImageUrl;
    }

    public String getFacilitiesImageUrl() {
        return facilitiesImageUrl;
    }

    public void setFacilitiesImageUrl(String facilitiesImageUrl) {
        this.facilitiesImageUrl = facilitiesImageUrl;
    }

    public String getSectionTrainingTitle() {
        return sectionTrainingTitle;
    }

    public void setSectionTrainingTitle(String sectionTrainingTitle) {
        this.sectionTrainingTitle = sectionTrainingTitle;
    }

    public String getSectionTrainingBody() {
        return sectionTrainingBody;
    }

    public void setSectionTrainingBody(String sectionTrainingBody) {
        this.sectionTrainingBody = sectionTrainingBody;
    }

    public String getSectionFacilitiesTitle() {
        return sectionFacilitiesTitle;
    }

    public void setSectionFacilitiesTitle(String sectionFacilitiesTitle) {
        this.sectionFacilitiesTitle = sectionFacilitiesTitle;
    }

    public String getSectionFacilitiesBody() {
        return sectionFacilitiesBody;
    }

    public void setSectionFacilitiesBody(String sectionFacilitiesBody) {
        this.sectionFacilitiesBody = sectionFacilitiesBody;
    }
}
