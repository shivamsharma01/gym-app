package com.example.gym.member;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A gym member (the person who trains). Distinct from an {@code AdminUser} (staff login).
 *
 * <p>Data minimisation: this table intentionally stores no biometric data — no face images and no
 * face/fingerprint templates. Biometric enrolment lives on the device; the application only keeps
 * an opaque device-user mapping (added in a later phase), never the biometric itself.
 */
@Entity
@Table(name = "member")
public class Member extends TenantAwareEntity {

    /** Human-friendly identifier, unique within a tenant (e.g. "MBR-9F3A2C"). */
    @Column(name = "member_code", nullable = false, length = 32)
    private String memberCode;

    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @Column(name = "last_name", length = 80)
    private String lastName;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, length = 16)
    private Gender gender = Gender.UNSPECIFIED;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MemberStatus status = MemberStatus.ACTIVE;

    @Column(name = "joined_on", nullable = false)
    private LocalDate joinedOn;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "creation_source", nullable = false, length = 16)
    private MemberCreationSource creationSource = MemberCreationSource.MANUAL;

    protected Member() {
    }

    public Member(Long tenantId, String memberCode, String firstName) {
        setTenantId(tenantId);
        this.memberCode = memberCode;
        this.firstName = firstName;
        this.joinedOn = LocalDate.now();
        this.creationSource = MemberCreationSource.MANUAL;
    }

    public String getMemberCode() {
        return memberCode;
    }

    public void setMemberCode(String memberCode) {
        this.memberCode = memberCode;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender == null ? Gender.UNSPECIFIED : gender;
    }

    public MemberStatus getStatus() {
        return status;
    }

    public void setStatus(MemberStatus status) {
        this.status = status;
    }

    public LocalDate getJoinedOn() {
        return joinedOn;
    }

    public void setJoinedOn(LocalDate joinedOn) {
        this.joinedOn = joinedOn;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public MemberCreationSource getCreationSource() {
        return creationSource;
    }

    public void setCreationSource(MemberCreationSource creationSource) {
        this.creationSource = creationSource == null ? MemberCreationSource.MANUAL : creationSource;
    }

    public String getFullName() {
        return lastName == null || lastName.isBlank() ? firstName : firstName + " " + lastName;
    }
}
