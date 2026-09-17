package com.example.gym.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberService;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import com.example.gym.notification.annoucement.AnnouncementRepository;
import com.example.gym.notification.annoucement.entity.Announcement;
import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.channel.adapter.NotificationChannelAdapter;
import com.example.gym.notification.dto.CreateAnnouncement;
import com.example.gym.notification.dto.SendNotification;
import com.example.gym.notification.dto.UpsertTemplate;
import com.example.gym.notification.outbound.OutboundNotification;
import com.example.gym.notification.outbound.repository.OutboundNotificationRepository;
import com.example.gym.notification.template.NotificationTemplate;
import com.example.gym.notification.template.NotificationTemplateKeys;
import com.example.gym.notification.template.TemplateRenderer;
import com.example.gym.notification.template.repository.NotificationTemplateRepository;
import com.example.gym.notification.utils.NotificationStatus;
import com.example.gym.notification.whatsapp.WhatsAppTemplateDefaults;
import com.example.gym.settings.GymProfileRepository;
import com.example.gym.tenant.Tenant;
import com.example.gym.tenant.TenantGuard;
import com.example.gym.tenant.TenantRepository;

@Service
public class NotificationService {

	private final NotificationTemplateRepository templateRepository;
	private final OutboundNotificationRepository outboundRepository;
	private final AnnouncementRepository announcementRepository;

	private final MemberService memberService;
	private final MemberRepository memberRepository;
	private final MembershipRepository membershipRepository;

	private final Map<NotificationChannel, NotificationChannelAdapter> adapters;

	private final AuditService auditService;
	private final TenantRepository tenantRepository;
	private final GymProfileRepository profileRepository;
	private final TemplateRenderer templateRenderer;

	public NotificationService(NotificationTemplateRepository templateRepository,
			OutboundNotificationRepository outboundRepository, AnnouncementRepository announcementRepository,
			MemberService memberService, MemberRepository memberRepository, MembershipRepository membershipRepository,
			List<NotificationChannelAdapter> adapterList, AuditService auditService, TenantRepository tenantRepository,
			GymProfileRepository profileRepository, TemplateRenderer templateRenderer) {

		this.templateRepository = templateRepository;

		this.outboundRepository = outboundRepository;

		this.announcementRepository = announcementRepository;

		this.memberService = memberService;

		this.memberRepository = memberRepository;

		this.membershipRepository = membershipRepository;

		this.adapters = adapterList.stream()
				.collect(Collectors.toUnmodifiableMap(NotificationChannelAdapter::channel, Function.identity()));

		this.auditService = auditService;

		this.tenantRepository = tenantRepository;

		this.profileRepository = profileRepository;

		this.templateRenderer = templateRenderer;
	}

	@Transactional(readOnly = true)
	public List<NotificationTemplate> templates(Long tenantId) {

		requireTenant(tenantId);

		return templateRepository.findByTenantIdOrderByTemplateKeyAsc(tenantId);
	}

	@Transactional
	public NotificationTemplate upsertTemplate(UpsertTemplate request, Long tenantId) {

		requireTenant(tenantId);

		NotificationTemplate template = templateRepository
				.findByTenantIdAndTemplateKeyAndChannel(tenantId, request.templateKey(), request.channel())
				.orElseGet(() -> new NotificationTemplate(tenantId, request.templateKey(), request.channel(),
						request.subject(), request.body()));

		template.setSubject(request.subject());

		template.setBody(request.body());
		if (request.channel() == NotificationChannel.WHATSAPP) {
			template.setWhatsappTemplateName(WhatsAppTemplateDefaults.TEMPLATE_NAME);
			template.setWhatsappLanguage(WhatsAppTemplateDefaults.LANGUAGE);
		} else {
			template.setWhatsappTemplateName(null);
			template.setWhatsappLanguage(null);
		}

		return templateRepository.save(template);
	}

	@Transactional
	public OutboundNotification send(SendNotification request, Long tenantId) {

		requireTenant(tenantId);

		Member member = memberService.getByPublicId(request.memberId(), tenantId);

		NotificationTemplate template = templateRepository
				.findByTenantIdAndTemplateKeyAndChannelAndActiveTrue(tenantId, request.templateKey(), request.channel())
				.orElseThrow(() -> CommonExceptions.notFound("Notification template"));

		String recipient = recipientFor(member, request.channel());

		Long membershipId = null;

		// membershipId is optional for normal/UI notifications.
		// If UI provides it, resolve the public ID to the internal DB ID.
		if (request.membershipId() != null && !request.membershipId().isBlank()) {
			Membership membership = membershipRepository.findByPublicId(request.membershipId())
					.orElseThrow(() -> CommonExceptions.notFound("Membership"));

			TenantGuard.check(membership.getTenantId(), tenantId, "Membership");

			// Optional safety check: membership must belong to this member.
			if (!membership.getMemberId().equals(member.getId())) {
				throw CommonExceptions.badRequest("Membership does not belong to the specified member");
			}

			membershipId = membership.getId();
		}

		Map<String, Object> variables = Map.of("memberName", member.getFullName(), "gymName", gymDisplayName(tenantId));

		return queueAndDeliver(tenantId, member.getId(), membershipId, request.channel(), template, recipient,
				variables);
	}

	@Transactional
	public OutboundNotification queueAndDeliver(Long tenantId, Long memberId, Long membershipId,
			NotificationChannel channel, String templateKey, String recipient, Map<String, Object> variables) {

		requireTenant(tenantId);

		NotificationTemplate template = templateRepository
				.findByTenantIdAndTemplateKeyAndChannelAndActiveTrue(tenantId, templateKey, channel)
				.orElseThrow(() -> CommonExceptions.notFound("Notification template"));

		return queueAndDeliver(tenantId, memberId, membershipId, channel, template, recipient, variables);
	}

	private OutboundNotification queueAndDeliver(Long tenantId, Long memberId, Long membershipId,
			NotificationChannel channel, NotificationTemplate template, String recipient,
			Map<String, Object> variables) {

		String subject = templateRenderer.render(template.getSubject(), variables);

		String body = templateRenderer.render(template.getBody(), variables);

		OutboundNotification notification = new OutboundNotification(tenantId, memberId, channel,
				template.getTemplateKey(), recipient, subject, body, template.getWhatsappTemplateName(),
				template.getWhatsappLanguage(), membershipId, null);

		notification = outboundRepository.save(notification);

		deliver(notification);

		return notification;
	}

	@Transactional
	protected void deliver(OutboundNotification notification) {

		NotificationChannelAdapter adapter = adapters.get(notification.getChannel());

		if (adapter == null) {

			markFailed(notification, "No adapter configured for channel " + notification.getChannel());

			outboundRepository.save(notification);

			return;
		}

		try {

			notification.setAttemptCount(notification.getAttemptCount() + 1);

			adapter.send(notification);

			notification.setStatus(NotificationStatus.SENT);

			notification.setSentAt(Instant.now());

			notification.setLastError(null);

		} catch (Exception ex) {

			String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();

			markFailed(notification, message);
		}

		outboundRepository.save(notification);
	}

	private void markFailed(OutboundNotification notification, String error) {

		notification.setStatus(NotificationStatus.FAILED);

		notification.setLastError(error);
	}

	@Transactional
	public int queueExpiryReminders(Long tenantId) {

		requireTenant(tenantId);

		LocalDate today = LocalDate.now();
		LocalDate expiryDate = today.plusDays(3);

		int queued = 0;

		List<Membership> memberships = membershipRepository.findByTenantIdAndEndDateAndStatus(tenantId, expiryDate,
				MembershipStatus.ACTIVE);

		for (Membership membership : memberships) {

			Member member = memberRepository.findById(membership.getMemberId()).orElse(null);

			if (member == null) {
				continue;
			}

			TenantGuard.check(member, tenantId, "Member");

			if (member.getPhone() == null || member.getPhone().isBlank()) {
				continue;
			}

			String templateKey = NotificationTemplateKeys.EXPIRY_REMINDER_3_DAYS;

			boolean alreadyQueued = outboundRepository.existsByMembershipIdAndTemplateKeyAndChannel(membership.getId(),
					templateKey, NotificationChannel.WHATSAPP);

			if (alreadyQueued) {
				continue;
			}

			NotificationTemplate whatsappTemplate = templateRepository
					.findByTenantIdAndTemplateKeyAndChannelAndActiveTrue(tenantId, templateKey,
							NotificationChannel.WHATSAPP)
					.orElse(null);

			if (whatsappTemplate == null) {
				continue;
			}

			Map<String, Object> variables = Map.ofEntries(Map.entry("memberName", member.getFullName()),
					Map.entry("memberCode", member.getMemberCode()), Map.entry("gymName", gymDisplayName(tenantId)),
					Map.entry("membershipPlan", membership.getPlanName()),
					Map.entry("startDate", membership.getStartDate().toString()),
					Map.entry("expiryDate", membership.getEndDate().toString()), Map.entry("daysRemaining", 3),
					Map.entry("amount", membership.getPrice()), Map.entry("currency", membership.getCurrency()),
					Map.entry("amountPaid", membership.getAmountPaid()),
					Map.entry("membershipStatus", membership.effectiveStatus(today).name()));

			queueAndDeliver(tenantId, member.getId(), membership.getId(), NotificationChannel.WHATSAPP,
					whatsappTemplate, member.getPhone(), variables);

			queued++;
		}
		return queued;
	}

	@Transactional
	public int queueExpiryRemindersForAllTenants() {

		int total = 0;

		for (Tenant tenant : tenantRepository.findAll()) {
			total += queueExpiryReminders(tenant.getId());
		}

		return total;
	}

	@Transactional
	public int retryFailedNotificationsForAllTenants() {

		int totalRetried = 0;

		for (Tenant tenant : tenantRepository.findAll()) {

			try {
				totalRetried += retryFailedNotifications(tenant.getId());
			} catch (Exception ex) {
				// log tenant-specific failure
			}
		}

		return totalRetried;
	}

	@Transactional
	public int retryFailedNotifications(Long tenantId) {

		requireTenant(tenantId);

		int maxAttempts = 3;

		List<OutboundNotification> failed = outboundRepository.findByTenantIdAndStatusAndAttemptCountLessThan(tenantId,
				NotificationStatus.FAILED, maxAttempts);

		int retried = 0;

		for (OutboundNotification notification : failed) {

			deliver(notification);
			retried++;
		}

		return retried;
	}

	@Transactional(readOnly = true)
	public Page<OutboundNotification> outbound(Long tenantId, Pageable pageable) {

		requireTenant(tenantId);

		return outboundRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
	}

	@Transactional
	public Announcement createAnnouncement(CreateAnnouncement request, Long tenantId) {

		requireTenant(tenantId);

		Announcement announcement = announcementRepository
				.save(new Announcement(tenantId, request.title(), request.body(), request.published()));

		auditService.record(AuditActions.ANNOUNCEMENT_CREATED, AuditActions.RESULT_SUCCESS, "Announcement",
				announcement.getPublicId(), null);

		if (request.published()) {
			queueAnnouncementWhatsApp(announcement, tenantId);
		}

		return announcement;
	}

	@Transactional
	protected int queueAnnouncementWhatsApp(Announcement announcement, Long tenantId) {

		List<Membership> memberships = membershipRepository.findByTenantIdAndStatus(tenantId, MembershipStatus.ACTIVE);

		int queued = 0;

		for (Membership membership : memberships) {

			Member member = memberRepository.findById(membership.getMemberId()).orElse(null);

			if (member == null) {
				continue;
			}

			TenantGuard.check(member, tenantId, "Member");

			if (member.getPhone() == null || member.getPhone().isBlank()) {
				continue;
			}

			/*
			 * Prevent the same announcement from being queued twice for the same member.
			 */
			boolean alreadyQueued = outboundRepository.existsByTenantIdAndMemberIdAndAnnouncementIdAndChannel(tenantId,
					member.getId(), announcement.getId(), NotificationChannel.WHATSAPP);

			if (alreadyQueued) {
				continue;
			}

			Map<String, Object> variables = Map.of("memberName", member.getFullName(), "memberCode",
					member.getMemberCode(), "gymName", gymDisplayName(tenantId));

			String body = templateRenderer.render(announcement.getBody(), variables);

			OutboundNotification notification = new OutboundNotification(tenantId, member.getId(),
					NotificationChannel.WHATSAPP, NotificationTemplateKeys.ANNOUNCEMENT, member.getPhone(),
					announcement.getTitle(), body, WhatsAppTemplateDefaults.TEMPLATE_NAME,
					WhatsAppTemplateDefaults.LANGUAGE, null, // membershipId - announcement is not membership-specific
					announcement.getId());

			notification = outboundRepository.save(notification);

			deliver(notification);

			queued++;
		}

		return queued;
	}

	@Transactional(readOnly = true)
	public List<Announcement> announcements(Long tenantId) {

		requireTenant(tenantId);

		return announcementRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
	}

	private String recipientFor(Member member, NotificationChannel channel) {

		return switch (channel) {

		case EMAIL -> {

			if (member.getEmail() == null || member.getEmail().isBlank()) {

				throw CommonExceptions.badRequest("Member has no email");
			}

			yield member.getEmail();
		}

		case WHATSAPP -> {

			if (member.getPhone() == null || member.getPhone().isBlank()) {

				throw CommonExceptions.badRequest("Member has no phone");
			}

			yield member.getPhone();
		}

		case IN_APP -> member.getPublicId();
		};
	}

	private String gymDisplayName(Long tenantId) {

		if (tenantId == null) {
			return "Gym";
		}

		return profileRepository.findByTenantId(tenantId).map(p -> p.getDisplayName())
				.filter(name -> name != null && !name.isBlank())
				.or(() -> tenantRepository.findById(tenantId).map(t -> t.getName())).orElse("Gym");
	}

	private void requireTenant(Long tenantId) {

		if (tenantId == null) {

			throw CommonExceptions.badRequest("A gym tenant is required");
		}
	}
}
