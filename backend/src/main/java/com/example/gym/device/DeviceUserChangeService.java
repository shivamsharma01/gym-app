package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.device.DeviceAuthorizationService.AccessWindow;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.ReconciliationConflict;
import com.example.gym.device.domain.ReconciliationConflictStatus;
import com.example.gym.device.domain.ReconciliationConflictType;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.device.repo.DeviceSyncCommandRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.ReconciliationConflictRepository;
import com.example.gym.face.GatewayFaceUpload;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceService;
import com.example.gym.live.StaffLiveBroadcast;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberService;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipService;
import com.example.gym.membership.MembershipStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Applies users created, edited or deleted directly on a device (DEVICE_USER_CHANGED) and fans the
 * result out to every device. Gateways apply the same rules locally, so devices on one gateway are
 * already in sync; the server reconciles gateway changes with its own. Rules:
 * <ul>
 *   <li>Only the fields the device reports as changed are considered; untouched fields never
 *       overwrite the server.</li>
 *   <li>A change made on one side only is applied. When both sides changed the same thing, the later
 *       change wins (device change time vs the server's change time for that field). Every change
 *       that loses is recorded as {@link AuditActions#SYNC_CHANGE_IGNORED}.</li>
 *   <li>When the server's result differs from what the device holds (a rule refused the change, a
 *       deletion lost, ...) the server's change time is set to now so gateways take its version.</li>
 *   <li>Unknown user: becomes a DEVICE_IMPORT member with an "Unknown" plan membership. A user new to
 *       the gateway whose id is another member's code (enrolled offline) becomes a separate member
 *       with a new code and is flagged ({@link ReconciliationConflictType#DEVICE_CODE_CLASH}).</li>
 *   <li>Deleted on a device: the member is deactivated (history is kept) and removed from all
 *       devices. A later change from any device brings the member back.</li>
 *   <li>A face whose sha256 matches the stored one is an echo and is ignored.</li>
 * </ul>
 */
@Service
public class DeviceUserChangeService {

    private static final Logger log = LoggerFactory.getLogger(DeviceUserChangeService.class);
    private static final String DEVICE_ACTOR = "device";

    private final MemberRepository memberRepository;
    private final MemberService memberService;
    private final MemberDeviceMappingRepository mappingRepository;
    private final ReconciliationConflictRepository conflictRepository;
    private final DeviceSyncCommandRepository commandRepository;
    private final MembershipService membershipService;
    private final DeviceAuthorizationService authorizationService;
    private final DeviceMemberImporter importer;
    private final MemberFaceService faceService;
    private final MemberDeviceProvisioningService provisioning;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;

    public DeviceUserChangeService(MemberRepository memberRepository,
                                   MemberService memberService,
                                   MemberDeviceMappingRepository mappingRepository,
                                   ReconciliationConflictRepository conflictRepository,
                                   DeviceSyncCommandRepository commandRepository,
                                   MembershipService membershipService,
                                   DeviceAuthorizationService authorizationService,
                                   DeviceMemberImporter importer,
                                   MemberFaceService faceService,
                                   MemberDeviceProvisioningService provisioning,
                                   AuditService auditService,
                                   ApplicationEventPublisher events) {
        this.memberRepository = memberRepository;
        this.memberService = memberService;
        this.mappingRepository = mappingRepository;
        this.conflictRepository = conflictRepository;
        this.commandRepository = commandRepository;
        this.membershipService = membershipService;
        this.authorizationService = authorizationService;
        this.importer = importer;
        this.faceService = faceService;
        this.provisioning = provisioning;
        this.auditService = auditService;
        this.events = events;
    }

    /** What a device reported, parsed once. */
    private record Report(String deviceUserId, Instant changedAt, String name, boolean frozen,
                          LocalDate validFrom, LocalDate validTo, boolean isNew, boolean nameChanged,
                          boolean frozenChanged, boolean validityChanged, boolean faceChanged,
                          boolean faceRemoved, GatewayFaceUpload upload, Instant faceChangedAt,
                          com.example.gym.member.DeviceAuthority authority, boolean authorityChanged,
                          boolean initialSample) {
    }

    @Transactional
    public void apply(Device device, JsonNode payload) {
        String deviceUserId = text(payload, "deviceUserId");
        if (deviceUserId == null || deviceUserId.isBlank()) {
            return;
        }
        Instant changedAt = parseInstant(text(payload, "deviceChangedAt"));
        if (changedAt == null || changedAt.isAfter(Instant.now())) {
            changedAt = Instant.now();
        }
        Member member = findMember(device, deviceUserId).orElse(null);

        if (bool(payload, "deleted")) {
            if (member != null) {
                applyDeletion(device, member, deviceUserId, changedAt);
            }
            return;
        }

        Report r = parse(device, payload, deviceUserId, changedAt);
        if (member == null) {
            createFromDevice(device, r);
            return;
        }
        if (r.isNew() && !DeviceMemberImporter.sameDeviceName(r.name(), member.getFullName())) {
            applyCodeClash(device, member, r);
            return;
        }

        List<String> ignored = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean revived = false;
        if (isDeleted(member)) {
            if (!isAfter(changedAt, member.getAccessChangedAt())) {
                provisioning.removeFrom(member, device.getId(), deviceUserId);
                ignored.add("Change on device at %s ignored: the member was deleted at %s"
                        .formatted(changedAt, member.getAccessChangedAt()));
                finish(device, member, deviceUserId, "SERVER_KEPT", false, notes, ignored);
                return;
            }
            // Re-created / edited on a device after the deletion: the member is back.
            member.setStatus(MemberStatus.ACTIVE);
            member.setAccessChangedAt(changedAt);
            memberRepository.save(member);
            revived = true;
            notes.add("Member deleted earlier was re-created on the device; reactivated");
        }

        if (mappingRepository.findByDeviceIdAndMemberId(device.getId(), member.getId()).isEmpty()) {
            provisioning.markHeldBySource(member, device, deviceUserId, null);
            closeExtraConflict(device, deviceUserId);
        }

        boolean updated = revived;
        boolean serverKept = false;
        boolean faceKept = false;

        if (r.nameChanged() && !DeviceMemberImporter.sameDeviceName(r.name(), member.getFullName())) {
            if (isAfter(changedAt, member.getProfileChangedAt())) {
                DeviceMemberImporter.NameParts parts = DeviceMemberImporter.parseName(r.name(), deviceUserId);
                member.setFirstName(parts.firstName());
                member.setLastName(parts.lastName());
                member.setProfileChangedAt(changedAt);
                memberRepository.save(member);
                provisioning.pushProfile(member, Set.of(device.getId()));
                updated = true;
            } else {
                serverKept = true;
                ignored.add("Name '%s' from the device (%s) ignored: name '%s' changed on the server at %s"
                        .formatted(r.name(), changedAt, member.getFullName(), member.getProfileChangedAt()));
            }
        }

        if (r.authorityChanged() && r.authority() != null && r.authority() != member.getDeviceAuthority()) {
            if (isAfter(changedAt, member.getProfileChangedAt())) {
                member.setDeviceAuthority(r.authority());
                member.setProfileChangedAt(changedAt);
                memberRepository.save(member);
                provisioning.pushProfile(member, Set.of(device.getId()));
                if (r.authority() == com.example.gym.member.DeviceAuthority.ADMIN) {
                    importer.ensureUnknownMembership(member, r.validFrom(), r.validTo());
                }
                updated = true;
                notes.add("Authority set to %s from device %s".formatted(r.authority(), device.getPublicId()));
            } else {
                serverKept = true;
                ignored.add("Authority '%s' from device (%s) ignored: profile changed on server at %s"
                        .formatted(r.authority(), changedAt, member.getProfileChangedAt()));
            }
        }

        if (r.frozenChanged() || r.validityChanged()) {
            if (isAfter(changedAt, member.getAccessChangedAt()) || revived) {
                updated |= applyAccess(device, member, r, notes);
            } else {
                serverKept = true;
                ignored.add("Access (frozen=%s, %s..%s) from the device (%s) ignored: access changed on the server at %s"
                        .formatted(r.frozen(), r.validFrom(), r.validTo(), changedAt, member.getAccessChangedAt()));
            }
        }

        boolean faceApplied = false;
        if (r.faceChanged()) {
            Optional<MemberFace> current = faceService.find(member.getId());
            Instant serverFaceAt = member.getFaceChangedAt() != null ? member.getFaceChangedAt()
                    : current.map(MemberFace::getChangedAt).orElse(null);
            if (r.faceRemoved() || r.upload() == null) {
                if (current.isPresent()) {
                    if (isAfter(changedAt, serverFaceAt)) {
                        faceService.removeFromDevice(member, device.getId(), changedAt);
                        updated = true;
                    } else {
                        provisioning.repushFace(member, device.getId());
                        faceKept = true;
                        ignored.add("Face removal on the device (%s) ignored: face changed on the server at %s"
                                .formatted(changedAt, serverFaceAt));
                    }
                }
            } else if (current.isPresent() && current.get().getSha256().equals(r.upload().getSha256())) {
                provisioning.markHeldBySource(member, device, deviceUserId, current.get().getFaceVersion());
            } else if (isAfter(changedAt, serverFaceAt)) {
                MemberFace face = faceService.applyFromDevice(member, r.upload(), device.getId(), r.faceChangedAt());
                provisioning.markHeldBySource(member, device, deviceUserId, face.getFaceVersion());
                // A first photo read is stored only. A later face edit is copied to the other readers.
                if (!r.initialSample()) {
                    provisioning.pushFace(member, face,
                            r.faceChangedAt().equals(changedAt) ? Set.of(device.getId()) : Set.of());
                }
                faceApplied = true;
                updated = true;
            } else {
                provisioning.repushFace(member, device.getId());
                faceKept = true;
                ignored.add("Face from the device (%s) ignored: face changed on the server at %s"
                        .formatted(changedAt, serverFaceAt));
            }
        }

        if (revived) {
            provisioning.provisionMember(member, Set.of(device.getId()));
        }
        if (serverKept) {
            // Server-side change is newer for at least one field: put the server's view back.
            provisioning.repushUser(member, device.getId());
        }
        boolean kept = serverKept || faceKept;
        String outcome = updated ? (kept ? "PARTIAL" : "UPDATED") : kept ? "SERVER_KEPT" : "UNCHANGED";
        finish(device, member, deviceUserId, outcome, faceApplied, notes, ignored);
    }

    /**
     * Reconcile found a user on the device that the server does not map. Links it to the member with
     * the same code, or imports it as a new member. The reader is left unchanged; its photo is
     * uploaded later by the gateway's own face pass.
     * Returns false when the user was left alone (e.g. a removal is still in flight, or the member
     * was deleted/deactivated on the server, which needs a staff decision).
     */
    @Transactional
    public boolean importFromReconcile(Device device, String deviceUserId, String name, boolean frozen,
                                       LocalDate validFrom, LocalDate validTo) {
        if (commandRepository.existsByDeviceIdAndTypeAndStateIn(
                device.getId(), SyncCommandType.REMOVE_USER, DeviceSyncService.OPEN_STATES)) {
            return false;
        }
        Optional<Member> existing = findMember(device, deviceUserId);
        Member member;
        if (existing.isPresent()) {
            member = existing.get();
            if (member.getStatus() != MemberStatus.ACTIVE
                    || mappingRepository.findByDeviceIdAndMemberId(device.getId(), member.getId()).isPresent()) {
                return false;
            }
        } else {
            member = importer.createMember(device.getTenantId(), deviceUserId, name, frozen);
            importer.ensureUnknownMembership(member, validFrom, validTo);
        }
        provisioning.markHeldBySource(member, device, deviceUserId, null);
        closeExtraConflict(device, deviceUserId);
        finish(device, member, deviceUserId, existing.isPresent() ? "LINKED" : "CREATED", false,
                List.of(), List.of());
        return true;
    }

    // --- cases -----------------------------------------------------------------------------------

    private void createFromDevice(Device device, Report r) {
        Member member = importer.createMember(device.getTenantId(), r.deviceUserId(), r.name(), r.frozen(), r.authority());
        member.setProfileChangedAt(r.changedAt());
        member.setAccessChangedAt(r.changedAt());
        member.setFaceChangedAt(r.changedAt());
        memberRepository.save(member);
        importer.ensureUnknownMembership(member, r.validFrom(), r.validTo());
        Integer faceVersion = null;
        if (r.upload() != null) {
            faceVersion = faceService.applyFromDevice(member, r.upload(), device.getId(), r.faceChangedAt())
                    .getFaceVersion();
        }
        provisioning.markHeldBySource(member, device, r.deviceUserId(), faceVersion);
        provisioning.provisionMember(member, Set.of(device.getId()));
        closeExtraConflict(device, r.deviceUserId());
        finish(device, member, r.deviceUserId(), "CREATED", faceVersion != null, List.of(), List.of());
    }

    /**
     * The gateway had never seen this user id, yet it is another member's code: someone was enrolled
     * on a device while it was offline. Keep them apart: the device user becomes a new member with a
     * new code on every device, and the existing member is written back to every device under its
     * code (with fresh change times so gateways replace the device user). Flagged for staff.
     */
    private void applyCodeClash(Device device, Member existing, Report r) {
        String serial = memberService.nextSerial(device.getTenantId());
        Member created = importer.createMemberWithSerial(device.getTenantId(), serial, r.deviceUserId(), r.name(),
                r.frozen(), r.authority());
        created.setProfileChangedAt(r.changedAt());
        created.setAccessChangedAt(r.changedAt());
        created.setFaceChangedAt(r.changedAt());
        memberRepository.save(created);
        importer.ensureUnknownMembership(created, r.validFrom(), r.validTo());
        if (r.upload() != null) {
            faceService.applyFromDevice(created, r.upload(), device.getId(), r.faceChangedAt());
        }
        provisioning.provisionMember(created, Set.of());

        Instant now = Instant.now();
        existing.setProfileChangedAt(now);
        existing.setAccessChangedAt(now);
        existing.setFaceChangedAt(now);
        memberRepository.save(existing);
        provisioning.reseedEverywhere(existing);

        String details = ("User '%s' was enrolled on %s while offline with id %s, which belongs to member %s (%s). "
                + "It was saved as a separate member %s (%s); the device user id is now %s.")
                .formatted(r.name(), device.getName(), r.deviceUserId(), existing.getMemberCode(),
                        existing.getFullName(), created.getMemberCode(), created.getFullName(), serial);
        conflictRepository.save(new ReconciliationConflict(device.getTenantId(), device.getId(), r.deviceUserId(),
                ReconciliationConflictType.DEVICE_CODE_CLASH, details));
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("device", device.getName());
        audit.put("deviceUserId", r.deviceUserId());
        audit.put("existingMember", existing.getPublicId());
        audit.put("existingSerialNumber", existing.getSerialNumber());
        audit.put("newMember", created.getPublicId());
        audit.put("newCode", created.getMemberCode());
        audit.put("serialNumber", serial);
        auditService.recordSystem(AuditActions.DEVICE_CODE_CLASH, AuditActions.RESULT_SUCCESS,
                "Member", created.getPublicId(), device.getTenantId(), DEVICE_ACTOR, audit);
        broadcast(device, created, "CREATED");
        broadcast(device, existing, "SERVER_KEPT");
        log.warn(details);
    }

    private void applyDeletion(Device device, Member member, String deviceUserId, Instant changedAt) {
        if (mappingRepository.findByDeviceIdAndMemberId(device.getId(), member.getId()).isEmpty()) {
            // Not ours on this device (e.g. already removed): nothing to propagate.
            return;
        }
        Instant serverChangedAt = latest(member.getProfileChangedAt(), member.getAccessChangedAt(),
                member.getFaceChangedAt());
        if (!isAfter(changedAt, serverChangedAt)) {
            // The member changed on the server after the deletion: it stays. Fresh change times make
            // gateways (which already removed it from their devices) take it back.
            Instant now = Instant.now();
            member.setProfileChangedAt(now);
            member.setAccessChangedAt(now);
            member.setFaceChangedAt(now);
            memberRepository.save(member);
            provisioning.reseedEverywhere(member);
            finish(device, member, deviceUserId, "SERVER_KEPT", false,
                    List.of("Re-created on every device"),
                    List.of("Deletion on the device (%s) ignored: the member changed on the server at %s"
                            .formatted(changedAt, serverChangedAt)));
            return;
        }
        member.setStatus(MemberStatus.INACTIVE);
        member.setAccessChangedAt(changedAt);
        memberRepository.save(member);
        provisioning.removeEverywhere(member);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("device", device.getName());
        details.put("deviceUserId", deviceUserId);
        details.put("serialNumber", member.getSerialNumber());
        auditService.recordSystem(AuditActions.MEMBER_DELETED_ON_DEVICE, AuditActions.RESULT_SUCCESS,
                "Member", member.getPublicId(), device.getTenantId(), DEVICE_ACTOR, details);
        broadcast(device, member, "DELETED");
        log.info("Device {} deleted user {} -> member {} deactivated and removed from all devices",
                device.getPublicId(), deviceUserId, member.getPublicId());
    }

    /**
     * Applies frozen / validity edited on the device (only the device changed this, or its change is
     * the later one). Dates are applied as the device holds them; disabling freezes, enabling
     * unfreezes. A device enable is refused only when the member or membership cannot be active
     * (inactive account, frozen, ended, or not started). Payment is not a reason to disable.
     * Whenever the result
     * differs from what the device holds, the access change time becomes now so gateways take the
     * server's version.
     */
    private boolean applyAccess(Device device, Member member, Report r, List<String> notes) {
        boolean changed = SyncClock.at(r.changedAt(), () -> applyMembershipChanges(device, member, r, notes));

        Optional<AccessWindow> window = authorizationService.window(member);
        boolean enabled = window.map(AccessWindow::enabled).orElse(false);
        boolean matchesDevice = enabled == !r.frozen()
                && window.map(w -> (r.validFrom() == null || r.validFrom().equals(w.validFrom()))
                        && (r.validTo() == null || r.validTo().equals(w.validTo()))).orElse(true);
        if (!enabled && !r.frozen()) {
            notes.add("Enabled on the device but refused (" + refusalReason(member, window) + "); disabled again");
        }
        if (matchesDevice) {
            member.setAccessChangedAt(r.changedAt());
            memberRepository.save(member);
            SyncClock.at(r.changedAt(), () -> authorizationService.refresh(member));
        } else {
            member.setAccessChangedAt(Instant.now());
            memberRepository.save(member);
            authorizationService.syncMember(member);
        }
        return changed;
    }

    private static String refusalReason(Member member, Optional<AccessWindow> window) {
        if (member.getStatus() != MemberStatus.ACTIVE) {
            return "member is inactive";
        }
        if (window.isEmpty()) {
            return "no membership";
        }
        Membership m = window.get().first();
        if (window.get().validTo().isBefore(LocalDate.now())) {
            return "membership ended on " + window.get().validTo();
        }
        if (m.getStatus() == MembershipStatus.FROZEN) {
            return "membership is frozen";
        }
        return "membership has not started";
    }

    private boolean applyMembershipChanges(Device device, Member member, Report r, List<String> notes) {
        boolean changed = false;
        LocalDate today = LocalDate.now();

        if (r.frozenChanged() && !r.frozen() && member.getStatus() != MemberStatus.ACTIVE) {
            member.setStatus(MemberStatus.ACTIVE);
            memberRepository.save(member);
            provisioning.provisionMember(member, Set.of(device.getId()));
            changed = true;
        }
        if (r.frozenChanged() && !r.frozen()) {
            Membership first = authorizationService.window(member).map(AccessWindow::first).orElse(null);
            if (first != null && first.getStatus() == MembershipStatus.FROZEN && first.getFrozenOn() != null) {
                membershipService.unfreeze(first.getPublicId(), member.getTenantId());
                changed = true;
            }
        }

        LocalDate from = r.validFrom();
        LocalDate to = r.validTo();
        if (r.validityChanged() && from != null && to != null && !to.isBefore(from)) {
            Optional<AccessWindow> window = authorizationService.window(member);
            if (window.isEmpty()) {
                importer.ensureUnknownMembership(member, from, to);
                changed = true;
            } else {
                changed |= applyDates(device, member, r.deviceUserId(), window.get(), from, to, notes);
            }
        }

        if (r.frozenChanged() && r.frozen() && member.getStatus() == MemberStatus.ACTIVE) {
            Membership first = authorizationService.window(member).map(AccessWindow::first).orElse(null);
            if (first != null && first.effectiveStatus(today) == MembershipStatus.ACTIVE) {
                membershipService.freeze(first.getPublicId(), member.getTenantId());
                changed = true;
            } else if (first != null && first.effectiveStatus(today) == MembershipStatus.PENDING) {
                notes.add("Disabled on the device, but the membership has not started, so it cannot be frozen");
            }
        }
        return changed;
    }

    /**
     * The device window may join several memberships: a new start date belongs to the first, a new
     * end date to the last. Overlaps with other memberships are applied and flagged for staff.
     */
    private boolean applyDates(Device device, Member member, String deviceUserId, AccessWindow w,
                               LocalDate from, LocalDate to, List<String> notes) {
        Membership first = w.first();
        Membership last = w.last();
        boolean changed = false;
        boolean overlap = false;
        if (first == last) {
            if (!from.equals(first.getStartDate()) || !to.equals(first.getEndDate())) {
                overlap = membershipService.applyDatesFromDevice(first, from, to);
                changed = true;
            }
        } else {
            if (!from.equals(w.validFrom())) {
                if (from.isAfter(first.getEndDate())) {
                    notes.add("Start date " + from + " from the device not applied: it is after the end of the "
                            + "first membership in the window (" + first.getEndDate() + ")");
                } else {
                    overlap |= membershipService.applyDatesFromDevice(first, from, first.getEndDate());
                    changed = true;
                }
            }
            if (!to.equals(w.validTo())) {
                if (to.isBefore(last.getStartDate())) {
                    notes.add("End date " + to + " from the device not applied: it is before the start of the "
                            + "last membership in the window (" + last.getStartDate() + ")");
                } else {
                    overlap |= membershipService.applyDatesFromDevice(last, last.getStartDate(), to);
                    changed = true;
                }
            }
        }
        if (overlap) {
            String details = ("Dates %s to %s set on %s for member %s (%s) now overlap another membership. "
                    + "Adjust the memberships in the app.")
                    .formatted(from, to, device.getName(), member.getMemberCode(), member.getFullName());
            conflictRepository.save(new ReconciliationConflict(device.getTenantId(), device.getId(), deviceUserId,
                    ReconciliationConflictType.MEMBERSHIP_OVERLAP, details));
            notes.add("Dates from the device overlap another membership; flagged for staff");
        }
        return changed;
    }

    // --- helpers ---------------------------------------------------------------------------------

    private Report parse(Device device, JsonNode payload, String deviceUserId, Instant changedAt) {
        boolean profileChanged = bool(payload, "profileChanged");
        // Older gateways only send profileChanged; treat it as "every field may have changed".
        boolean perField = payload.has("nameChanged");
        String uploadId = text(payload, "faceUploadId");
        GatewayFaceUpload upload = uploadId == null ? null
                : faceService.findUpload(uploadId, device.getTenantId()).orElse(null);
        if (uploadId != null && upload == null) {
            log.warn("Device {} reported face upload {} that does not exist", device.getPublicId(), uploadId);
        }
        // If the server re-encoded the device image, the stored face differs from what the device
        // holds: stamp it now so gateways replace the device copy with the server's.
        String deviceSha = text(payload, "faceSha256");
        Instant faceChangedAt = upload != null && deviceSha != null && !deviceSha.equalsIgnoreCase(upload.getSha256())
                ? Instant.now() : changedAt;
        String authorityText = text(payload, "authority");
        com.example.gym.member.DeviceAuthority auth = com.example.gym.member.DeviceAuthority.fromString(authorityText);
        boolean authorityChanged = payload.has("authorityChanged") ? bool(payload, "authorityChanged") : profileChanged;
        return new Report(
                deviceUserId,
                changedAt,
                text(payload, "name"),
                bool(payload, "frozen"),
                parseDate(text(payload, "validFrom")),
                parseDate(text(payload, "validTo")),
                bool(payload, "isNew"),
                perField ? bool(payload, "nameChanged") : profileChanged,
                perField ? bool(payload, "frozenChanged") : profileChanged,
                perField ? bool(payload, "validityChanged") : profileChanged,
                bool(payload, "faceChanged"),
                bool(payload, "faceRemoved"),
                upload,
                faceChangedAt,
                auth,
                authorityChanged,
                bool(payload, "initialSample"));
    }

    /** Deleted on a device: inactive and on no device (an admin deactivation keeps the mappings). */
    private boolean isDeleted(Member member) {
        return member.getStatus() != MemberStatus.ACTIVE && mappingRepository.findByMemberId(member.getId()).isEmpty();
    }

    private Optional<Member> findMember(Device device, String deviceUserId) {
        Optional<MemberDeviceMapping> mapping = mappingRepository.findByDeviceIdAndDeviceUserId(
                device.getId(), deviceUserId);
        if (mapping.isEmpty()) {
            mapping = mappingRepository.findFirstByDeviceIdAndPendingDeviceUserId(device.getId(), deviceUserId);
        }
        if (mapping.isPresent()) {
            return memberRepository.findById(mapping.get().getMemberId());
        }
        return importer.findBySerial(device.getTenantId(), deviceUserId);
    }

    private void closeExtraConflict(Device device, String deviceUserId) {
        for (ReconciliationConflict c : conflictRepository.findByDeviceIdAndStatusAndDeviceUserId(
                device.getId(), ReconciliationConflictStatus.OPEN, deviceUserId)) {
            if (c.getConflictType() == ReconciliationConflictType.EXTRA_DEVICE_USER) {
                c.resolve();
                conflictRepository.save(c);
            }
        }
    }

    private void finish(Device device, Member member, String deviceUserId, String outcome,
                        boolean faceApplied, List<String> notes, List<String> ignored) {
        if (!ignored.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("device", device.getName());
            details.put("deviceUserId", deviceUserId);
            details.put("serialNumber", member.getSerialNumber());
            details.put("ignored", ignored);
            auditService.recordSystem(AuditActions.SYNC_CHANGE_IGNORED, AuditActions.RESULT_SUCCESS,
                    "Member", member.getPublicId(), device.getTenantId(), DEVICE_ACTOR, details);
            ignored.forEach(reason -> log.info("Latest change wins, member {}: {}", member.getPublicId(), reason));
        }
        if ("UNCHANGED".equals(outcome) && notes.isEmpty()) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("device", device.getName());
        details.put("deviceUserId", deviceUserId);
        details.put("serialNumber", member.getSerialNumber());
        details.put("outcome", outcome);
        details.put("faceApplied", faceApplied);
        if (!notes.isEmpty()) {
            details.put("notes", notes);
        }
        auditService.recordSystem(AuditActions.MEMBER_CHANGED_ON_DEVICE, AuditActions.RESULT_SUCCESS,
                "Member", member.getPublicId(), device.getTenantId(), DEVICE_ACTOR, details);
        broadcast(device, member, outcome);
        log.info("Device {} user {} -> member {} ({}){}", device.getPublicId(), deviceUserId,
                member.getPublicId(), outcome, notes.isEmpty() ? "" : " " + notes);
    }

    private void broadcast(Device device, Member member, String outcome) {
        events.publishEvent(new StaffLiveBroadcast(device.getTenantId(), "MEMBER_SYNC", Map.of(
                "memberId", member.getPublicId(),
                "commandType", "DEVICE_USER_CHANGED",
                "state", outcome)));
    }

    private static boolean isAfter(Instant deviceTime, Instant serverTime) {
        return serverTime == null || deviceTime.isAfter(serverTime);
    }

    private static Instant latest(Instant... times) {
        return Stream.of(times).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static boolean bool(JsonNode node, String field) {
        return node != null && node.has(field) && node.get(field).asBoolean();
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        } catch (RuntimeException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(iso.length() >= 10 ? iso.substring(0, 10) : iso);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
