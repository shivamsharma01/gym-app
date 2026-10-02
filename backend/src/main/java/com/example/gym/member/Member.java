package com.example.gym.member;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A gym member (the person who trains). Distinct from an {@code AdminUser} (staff login).
 *
 * <p>Data minimisation: this table stores no biometric data. One face photo per member lives in
 * {@code member_face} (a resized JPEG on the faces volume, used to enrol the member on devices);
 * no face or fingerprint templates are ever stored — recognition happens on the device.
 */
@Entity
@Table(name = "member")
public class Member extends TenantAwareEntity {

    /** Human-friendly identifier, unique within a tenant (e.g. "MBR-9F3A2C"). */
    @Column(name = "member_code", nullable = false, length = 32)
    private String memberCode;

    /**
     * The id staff use for this member on readers (device user id). Editable; unique within a
     * tenant. Null for older members whose readers disagree on their id.
     */
    @Column(name = "serial_number", length = 32)
    private String serialNumber;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "device_authority", nullable = false, length = 32)
    private DeviceAuthority deviceAuthority = DeviceAuthority.USER;

    /** When the name last changed on the server (latest-change-wins vs devices). */
    @Column(name = "profile_changed_at")
    private Instant profileChangedAt;

    /** When access (membership dates, freeze, activation) last changed on the server. */
    @Column(name = "access_changed_at")
    private Instant accessChangedAt;

    /** When the face photo was last set or removed (on the server or a device). */
    @Column(name = "face_changed_at")
    private Instant faceChangedAt;

    /** Access window last sent to the member's devices; null until first sent. */
    @Column(name = "device_valid_from")
    private LocalDate deviceValidFrom;

    @Column(name = "device_valid_to")
    private LocalDate deviceValidTo;

    @Column(name = "device_enabled")
    private Boolean deviceEnabled;

    protected Member() {
    }

    public LocalDate getDeviceValidFrom() {
        return deviceValidFrom;
    }

    public LocalDate getDeviceValidTo() {
        return deviceValidTo;
    }

    public Boolean getDeviceEnabled() {
        return deviceEnabled;
    }

    public void setDeviceWindow(LocalDate validFrom, LocalDate validTo, Boolean enabled) {
        this.deviceValidFrom = validFrom;
        this.deviceValidTo = validTo;
        this.deviceEnabled = enabled;
    }

    public Member(Long tenantId, String memberCode, String firstName) {
        setTenantId(tenantId);
        this.memberCode = memberCode;
        this.firstName = firstName;
        this.joinedOn = LocalDate.now();
        this.creationSource = MemberCreationSource.MANUAL;
        this.profileChangedAt = micros(Instant.now());
        this.accessChangedAt = this.profileChangedAt;
        this.faceChangedAt = this.profileChangedAt;
    }

    public Instant getFaceChangedAt() {
        return faceChangedAt;
    }

    public void setFaceChangedAt(Instant faceChangedAt) {
        this.faceChangedAt = micros(faceChangedAt);
    }

    public Instant getAccessChangedAt() {
        return accessChangedAt;
    }

    public void setAccessChangedAt(Instant accessChangedAt) {
        this.accessChangedAt = micros(accessChangedAt);
    }

    public Instant getProfileChangedAt() {
        return profileChangedAt;
    }

    public void setProfileChangedAt(Instant profileChangedAt) {
        this.profileChangedAt = micros(profileChangedAt);
    }

    public String getMemberCode() {
        return memberCode;
    }

    public void setMemberCode(String memberCode) {
        this.memberCode = memberCode;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    /** The id new reader slots get: the serial, or the member code for members without one. */
    public String getDeviceUserId() {
        return serialNumber != null ? serialNumber : memberCode;
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

    public DeviceAuthority getDeviceAuthority() {
        return deviceAuthority;
    }

    public void setDeviceAuthority(DeviceAuthority deviceAuthority) {
        this.deviceAuthority = deviceAuthority == null ? DeviceAuthority.USER : deviceAuthority;
    }

    public String getFullName() {
        return lastName == null || lastName.isBlank() ? firstName : firstName + " " + lastName;
    }

    /** Change times are compared across server and gateways, so keep exactly what the database stores. */
    private static Instant micros(Instant at) {
        return at == null ? null : at.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
