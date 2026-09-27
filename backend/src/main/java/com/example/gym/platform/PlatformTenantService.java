package com.example.gym.platform;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import com.example.gym.platform.dto.EnrollTenantRequest;
import com.example.gym.platform.dto.EnrollTenantResponse;
import com.example.gym.platform.dto.TenantSummary;
import com.example.gym.security.domain.Role;
import com.example.gym.security.domain.RoleRepository;
import com.example.gym.settings.GymProfile;
import com.example.gym.settings.GymProfileRepository;
import com.example.gym.tenant.Tenant;
import com.example.gym.tenant.TenantRepository;
import com.example.gym.tenant.TenantStatus;
import com.example.gym.user.AdminUser;
import com.example.gym.user.AdminUserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformTenantService {

	private final TenantRepository tenantRepository;
	private final GymProfileRepository profileRepository;
	private final AdminUserRepository userRepository;
	private final RoleRepository roleRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuditService auditService;
    private final MemberRepository memberRepository;
    private final MembershipRepository membershipRepository;
    private final MemberDeviceMappingRepository mappingRepository;

    public PlatformTenantService(TenantRepository tenantRepository,
            					 GymProfileRepository profileRepository,
            					 AdminUserRepository userRepository,
            					 RoleRepository roleRepository,
            					 PasswordEncoder passwordEncoder,
            					 AuditService auditService,
            					 MemberRepository memberRepository,
            					 MembershipRepository membershipRepository,
            					 MemberDeviceMappingRepository mappingRepository) {
		this.tenantRepository = tenantRepository;
		this.profileRepository = profileRepository;
		this.userRepository = userRepository;
		this.roleRepository = roleRepository;
		this.passwordEncoder = passwordEncoder;
		this.auditService = auditService;
		this.memberRepository = memberRepository;
		this.membershipRepository = membershipRepository;
		this.mappingRepository = mappingRepository;
	}

    @Transactional(readOnly = true)
    public List<TenantSummary> list() {
        return tenantRepository.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(this::toSummary)
                .toList();
    }

	/**
	 * Returns the gym display name for a tenant.
	 *
	 * Priority: 1. GymProfile.displayName 2. Tenant.name 3. "Gym"
	 */
	@Transactional(readOnly = true)
	public String getDisplayName(Long tenantId) {
		if (tenantId == null) {
			return "Gym";
		}

		GymProfile profile = profileRepository.findByTenantId(tenantId).orElse(null);

		if (profile != null && profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
			return profile.getDisplayName();
		}

		return tenantRepository.findById(tenantId).map(Tenant::getName).filter(name -> name != null && !name.isBlank())
				.orElse("Gym");
	}

	@Transactional
	public EnrollTenantResponse enroll(EnrollTenantRequest request) {

		String slug = request.slug().trim().toLowerCase();

		if (tenantRepository.existsBySlug(slug)) {
			throw CommonExceptions.conflict("A gym with this slug already exists");
		}

		if (userRepository.existsByUsername(request.ownerUsername())) {
			throw CommonExceptions.conflict("Username already exists");
		}

		if (userRepository.existsByEmail(request.ownerEmail())) {
			throw CommonExceptions.conflict("Email already exists");
		}

		Role ownerRole = roleRepository.findByNameAndTenantIdIsNull("GYM_OWNER")
				.orElseThrow(() -> CommonExceptions.badRequest("System role GYM_OWNER is missing"));

		Tenant tenant = tenantRepository.save(new Tenant(request.name().trim(), slug));

		GymProfile profile = new GymProfile(tenant.getId());

		String display = request.displayName() == null || request.displayName().isBlank() ? request.name().trim()
				: request.displayName().trim();

		profile.setDisplayName(display);
		profile.setTagline("Train with intent.");
		profile.setAbout("Welcome to " + display + ". Membership, coaching, and door access in one place.");

		profileRepository.save(profile);

		AdminUser owner = new AdminUser(tenant.getId(), request.ownerUsername().trim(), request.ownerEmail().trim(),
				passwordEncoder.encode(request.ownerPassword()), request.ownerFullName().trim());

		owner.setRoles(Set.of(ownerRole));

		userRepository.save(owner);

		auditService.record(AuditActions.TENANT_ENROLLED, AuditActions.RESULT_SUCCESS, "Tenant", tenant.getPublicId(),
				Map.of("slug", slug, "owner", owner.getUsername()));

		return new EnrollTenantResponse(toSummary(tenant, profile), owner.getUsername(), owner.getEmail());
	}
	
    /**
     * Roster CSV for IAS migration handoff. No biometric / face template data.
     */
    @Transactional(readOnly = true)
    public String exportRosterCsv(String tenantPublicId) {
        Tenant tenant = tenantRepository.findByPublicId(tenantPublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Tenant"));
        LocalDate today = LocalDate.now();
        List<Member> members = memberRepository.search(tenant.getId(), null, null, null, Pageable.unpaged())
                .getContent();

        StringBuilder csv = new StringBuilder();
        csv.append("memberCode,firstName,lastName,status,creationSource,email,phone,")
                .append("planName,startDate,endDate,endDateInferred,deviceUserIds\n");

        for (Member m : members) {
            Optional<Membership> current = membershipRepository
                    .findByMemberIdAndDeletedFalseOrderByStartDateDesc(m.getId()).stream()
                    .filter(ms -> ms.getStatus() != MembershipStatus.CANCELLED)
                    .filter(ms -> ms.coversDate(today))
                    .findFirst();
            String deviceUserIds = mappingRepository.findByMemberId(m.getId()).stream()
                    .map(MemberDeviceMapping::getDeviceUserId)
                    .distinct()
                    .collect(Collectors.joining(";"));

            csv.append(csvEscape(m.getMemberCode())).append(',')
                    .append(csvEscape(m.getFirstName())).append(',')
                    .append(csvEscape(nullToEmpty(m.getLastName()))).append(',')
                    .append(csvEscape(m.getStatus().name())).append(',')
                    .append(csvEscape(m.getCreationSource().name())).append(',')
                    .append(csvEscape(nullToEmpty(m.getEmail()))).append(',')
                    .append(csvEscape(nullToEmpty(m.getPhone()))).append(',')
                    .append(csvEscape(current.map(Membership::getPlanName).orElse(""))).append(',')
                    .append(csvEscape(current.map(ms -> ms.getStartDate().toString()).orElse(""))).append(',')
                    .append(csvEscape(current.map(ms -> ms.getEndDate().toString()).orElse(""))).append(',')
                    .append(current.map(ms -> ms.isEndDateInferred() ? "true" : "false").orElse("")).append(',')
                    .append(csvEscape(deviceUserIds)).append('\n');
        }
        return csv.toString();
    }

    private TenantSummary toSummary(Tenant tenant) {
        GymProfile profile = profileRepository.findByTenantId(tenant.getId()).orElse(null);
        return toSummary(tenant, profile);
    }

    private TenantSummary toSummary(Tenant tenant, GymProfile profile) {
        String display = profile != null && profile.getDisplayName() != null && !profile.getDisplayName().isBlank()
                ? profile.getDisplayName()
                : tenant.getName();
        TenantStatus status = tenant.getStatus() == null ? TenantStatus.ACTIVE : tenant.getStatus();
        return new TenantSummary(tenant.getPublicId(), tenant.getName(), tenant.getSlug(), status.name(), display);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
