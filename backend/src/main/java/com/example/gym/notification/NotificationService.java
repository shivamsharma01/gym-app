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

import lombok.extern.slf4j.Slf4j;

@Slf4j
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

		log.info("Loading notification templates: tenantId={}", tenantId);

		requireTenant(tenantId);

		List<NotificationTemplate> templates = templateRepository.findByTenantIdOrderByTemplateKeyAsc(tenantId);

		log.info("Notification templates loaded: tenantId={}, count={}", tenantId, templates.size());

		return templates;
	}

	@Transactional
	public NotificationTemplate upsertTemplate(UpsertTemplate request, Long tenantId) {

		log.info("Upserting notification template: tenantId={}, templateKey={}, channel={}", tenantId,
				request.templateKey(), request.channel());

		requireTenant(tenantId);

		NotificationTemplate template = templateRepository
				.findByTenantIdAndTemplateKeyAndChannel(tenantId, request.templateKey(), request.channel())
				.orElseGet(() -> {
					log.info("Creating new notification template: tenantId={}, templateKey={}, channel={}", tenantId,
							request.templateKey(), request.channel());

					return new NotificationTemplate(tenantId, request.templateKey(), request.channel(),
							request.subject(), request.body());
				});

		template.setSubject(request.subject());

		template.setBody(request.body());
		if (request.channel() == NotificationChannel.WHATSAPP) {
			template.setWhatsappTemplateName(WhatsAppTemplateDefaults.TEMPLATE_NAME);
			template.setWhatsappLanguage(WhatsAppTemplateDefaults.LANGUAGE);

			log.info("WhatsApp template configuration applied: templateKey={}, whatsappTemplateName={}, language={}",
					request.templateKey(), WhatsAppTemplateDefaults.TEMPLATE_NAME, WhatsAppTemplateDefaults.LANGUAGE);
		} else {
			template.setWhatsappTemplateName(null);
			template.setWhatsappLanguage(null);
		}

		NotificationTemplate saved = templateRepository.save(template);

		log.info("Notification template saved: tenantId={}, id={}, templateKey={}, channel={}", tenantId,
				saved.getPublicId(), saved.getTemplateKey(), saved.getChannel());

		return saved;
	}

	@Transactional
	public OutboundNotification send(SendNotification request, Long tenantId) {

		log.info("Sending notification request: tenantId={}, memberId={}, membershipId={}, templateKey={}, channel={}",
				tenantId, request.memberId(), request.membershipId(), request.templateKey(), request.channel());
		requireTenant(tenantId);

		Member member = memberService.getByPublicId(request.memberId(), tenantId);

		log.info("Member resolved for notification: tenantId={}, memberId={}, memberPublicId={}", tenantId,
				member.getId(), request.memberId());

		NotificationTemplate template = templateRepository
				.findByTenantIdAndTemplateKeyAndChannelAndActiveTrue(tenantId, request.templateKey(), request.channel())
				.orElseThrow(() -> {
					log.error("Notification template not found: tenantId={}, templateKey={}, channel={}", tenantId,
							request.templateKey(), request.channel());

					return CommonExceptions.notFound("Notification template");
				});

		log.info(
				"Notification template resolved: tenantId={}, templateKey={}, channel={}, whatsappTemplate={}, whatsappLanguage={}",
				tenantId, template.getTemplateKey(), template.getChannel(), template.getWhatsappTemplateName(),
				template.getWhatsappLanguage());

		String recipient = recipientFor(member, request.channel());

		Long membershipId = null;

		// membershipId is optional for normal/UI notifications.
		// If UI provides it, resolve the public ID to the internal DB ID.
		if (request.membershipId() != null && !request.membershipId().isBlank()) {

			log.info("Resolving membership: tenantId={}, membershipPublicId={}, memberId={}", tenantId,
					request.membershipId(), member.getId());

			Membership membership = membershipRepository.findByPublicId(request.membershipId()).orElseThrow(() -> {
				log.error("Membership not found: tenantId={}, membershipPublicId={}", tenantId, request.membershipId());

				return CommonExceptions.notFound("Membership");
			});

			TenantGuard.check(membership.getTenantId(), tenantId, "Membership");

			// Optional safety check: membership must belong to this member.
			if (!membership.getMemberId().equals(member.getId())) {
				log.error(
						"Membership does not belong to member: tenantId={}, membershipId={}, membershipMemberId={}, requestedMemberId={}",
						tenantId, membership.getId(), membership.getMemberId(), member.getId());
				throw CommonExceptions.badRequest("Membership does not belong to the specified member");
			}

			membershipId = membership.getId();

			log.info("Membership resolved: tenantId={}, membershipId={}, memberId={}", tenantId, membershipId,
					member.getId());
		}

		Map<String, Object> variables = Map.of("memberName", member.getFullName(), "gymName", gymDisplayName(tenantId));

		log.info(
				"Queueing member notification: tenantId={}, memberId={}, membershipId={}, recipient={}, templateKey={}, channel={}",
				tenantId, member.getId(), membershipId, recipient, template.getTemplateKey(), request.channel());

		return queueAndDeliver(tenantId, member.getId(), membershipId, request.channel(), template, recipient,
				variables);
	}

	@Transactional
	public OutboundNotification queueAndDeliver(Long tenantId, Long memberId, Long membershipId,
			NotificationChannel channel, String templateKey, String recipient, Map<String, Object> variables) {

		log.info("Queueing notification: tenantId={}, memberId={}, membershipId={}, channel={}, templateKey={}",
				tenantId, memberId, membershipId, channel, templateKey);

		requireTenant(tenantId);

		NotificationTemplate template = templateRepository
				.findByTenantIdAndTemplateKeyAndChannelAndActiveTrue(tenantId, templateKey, channel).orElseThrow(() -> {
					log.error("Active notification template not found: tenantId={}, templateKey={}, channel={}",
							tenantId, templateKey, channel);

					return CommonExceptions.notFound("Notification template");
				});

		return queueAndDeliver(tenantId, memberId, membershipId, channel, template, recipient, variables);
	}

	private OutboundNotification queueAndDeliver(Long tenantId, Long memberId, Long membershipId,
			NotificationChannel channel, NotificationTemplate template, String recipient,
			Map<String, Object> variables) {

		log.info("Queueing notification: tenantId={}, memberId={}, membershipId={}, channel={}, templateKey={}",
				tenantId, memberId, membershipId, channel, template.getTemplateKey());

		String subject = templateRenderer.render(template.getSubject(), variables);

		String body = templateRenderer.render(template.getBody(), variables);

		log.debug("Notification template rendered: tenantId={}, templateKey={}, subjectLength={}, bodyLength={}",
				tenantId, template.getTemplateKey(), subject != null ? subject.length() : 0,
				body != null ? body.length() : 0);

		OutboundNotification notification = new OutboundNotification(tenantId, memberId, channel,
				template.getTemplateKey(), recipient, subject, body, template.getWhatsappTemplateName(),
				template.getWhatsappLanguage(), membershipId, null);

		notification = outboundRepository.save(notification);

		log.info(
				"Outbound notification saved: notificationId={}, tenantId={}, memberId={}, membershipId={}, templateKey={}, channel={}, whatsappTemplate={}, whatsappLanguage={}",
				notification.getPublicId(), tenantId, memberId, membershipId, template.getTemplateKey(), channel,
				notification.getWhatsappTemplateName(), notification.getWhatsappLanguage());

		deliver(notification);

		log.info("Outbound notification processing completed: notificationId={}, status={}, attemptCount={}",
				notification.getPublicId(), notification.getStatus(), notification.getAttemptCount());

		return notification;
	}

	@Transactional
	protected void deliver(OutboundNotification notification) {

		log.info(
				"Starting notification delivery: notificationId={}, tenantId={}, memberId={}, membershipId={}, channel={}, templateKey={}, recipient={}",
				notification.getPublicId(), notification.getTenantId(), notification.getMemberId(),
				notification.getMembershipId(), notification.getChannel(), notification.getTemplateKey(),
				notification.getRecipient());

		NotificationChannelAdapter adapter = adapters.get(notification.getChannel());

		if (adapter == null) {

			log.error("No notification adapter configured: notificationId={}, channel={}", notification.getPublicId(),
					notification.getChannel());

			markFailed(notification, "No adapter configured for channel " + notification.getChannel());

			outboundRepository.save(notification);

			return;
		}

		try {

			notification.setAttemptCount(notification.getAttemptCount() + 1);

			log.info("Calling notification adapter: notificationId={}, channel={}, attempt={}",
					notification.getPublicId(), notification.getChannel(), notification.getAttemptCount());

			adapter.send(notification);

			log.info("Notification sent: notificationId={}, tenantId={}, memberId={}, channel={}, templateKey={}",
					notification.getPublicId(), notification.getTenantId(), notification.getMemberId(),
					notification.getChannel(), notification.getTemplateKey());

			notification.setStatus(NotificationStatus.SENT);

			notification.setSentAt(Instant.now());

			notification.setLastError(null);

			log.info(
					"Notification delivered successfully: notificationId={}, tenantId={}, memberId={}, membershipId={}, channel={}, templateKey={}, attempt={}",
					notification.getPublicId(), notification.getTenantId(), notification.getMemberId(),
					notification.getMembershipId(), notification.getChannel(), notification.getTemplateKey(),
					notification.getAttemptCount());

		} catch (Exception ex) {

			String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();

			log.error(
					"Notification adapter failed: notificationId={}, tenantId={}, memberId={}, membershipId={}, channel={}, attempt={}, error={}",
					notification.getPublicId(), notification.getTenantId(), notification.getMemberId(),
					notification.getMembershipId(), notification.getChannel(), notification.getAttemptCount(), message,
					ex);

			markFailed(notification, message);
		}

		outboundRepository.save(notification);

		log.info("Notification delivery state saved: notificationId={}, status={}, attemptCount={}",
				notification.getPublicId(), notification.getStatus(), notification.getAttemptCount());
	}

	private void markFailed(OutboundNotification notification, String error) {

		notification.setStatus(NotificationStatus.FAILED);

		notification.setLastError(error);

		log.error("Notification delivery failed: notificationId={}, tenantId={}, memberId={}, attempt={}, error={}",
				notification.getPublicId(), notification.getTenantId(), notification.getMemberId(),
				notification.getAttemptCount(), notification.getLastError());
	}

	@Transactional
	public int queueExpiryReminders(Long tenantId) {

		log.info("Starting expiry reminder job: tenantId={}", tenantId);

		requireTenant(tenantId);

		LocalDate today = LocalDate.now();
		LocalDate expiryDate = today.plusDays(3);

		log.info("Expiry reminder dates: tenantId={}, today={}, expiryDate={}", tenantId, today, expiryDate);

		int queued = 0;
		String templateKey = NotificationTemplateKeys.EXPIRY_REMINDER_3_DAYS;

		NotificationTemplate whatsappTemplate = templateRepository.findByTenantIdAndTemplateKeyAndChannelAndActiveTrue(
				tenantId, templateKey, NotificationChannel.WHATSAPP).orElse(null);

		if (whatsappTemplate == null) {

			log.warn("Expiry reminder template not configured: tenantId={}, templateKey={}, channel=WHATSAPP", tenantId,
					templateKey);

			return 0;
		}

		log.info("Expiry reminder template found: tenantId={}, templateKey={}, whatsappTemplate={}, language={}",
				tenantId, templateKey, whatsappTemplate.getWhatsappTemplateName(),
				whatsappTemplate.getWhatsappLanguage());

		List<Membership> memberships = membershipRepository.findByTenantIdAndEndDateAndStatus(tenantId, expiryDate,
				MembershipStatus.ACTIVE);

		log.info("Expiry memberships found: tenantId={}, expiryDate={}, count={}", tenantId, expiryDate,
				memberships.size());

		for (Membership membership : memberships) {

			log.debug("Processing expiry membership: tenantId={}, membershipId={}, memberId={}", tenantId,
					membership.getId(), membership.getMemberId());

			Member member = memberRepository.findById(membership.getMemberId()).orElse(null);

			if (member == null) {
				log.warn("Skipping expiry reminder because member not found: tenantId={}, membershipId={}, memberId={}",
						tenantId, membership.getId(), membership.getMemberId());
				continue;
			}
			TenantGuard.check(member, tenantId, "Member");

			if (member.getPhone() == null || member.getPhone().isBlank()) {
				log.warn(
						"Skipping expiry reminder because member has no phone: tenantId={}, membershipId={}, memberId={}",
						tenantId, membership.getId(), member.getId());
				continue;
			}

			boolean alreadyQueued = outboundRepository.existsByMembershipIdAndTemplateKeyAndChannel(membership.getId(),
					templateKey, NotificationChannel.WHATSAPP);

			if (alreadyQueued) {
				log.info(
						"Expiry reminder already exists, skipping: tenantId={}, membershipId={}, memberId={}, templateKey={}",
						tenantId, membership.getId(), member.getId(), templateKey);
				continue;
			}

			Map<String, Object> variables = Map.ofEntries(Map.entry("memberName", member.getFullName()),
					Map.entry("memberCode", member.getMemberCode()), Map.entry("gymName", gymDisplayName(tenantId)),
					Map.entry("membershipPlan", membership.getPlanName()),
					Map.entry("startDate", membership.getStartDate().toString()),
					Map.entry("expiryDate", membership.getEndDate().toString()), Map.entry("daysRemaining", 3),
					Map.entry("amount", membership.getPrice()), Map.entry("currency", membership.getCurrency()),
					Map.entry("amountPaid", membership.getAmountPaid()),
					Map.entry("membershipStatus", MembershipStatus.ACTIVE));

			log.info(
					"Queueing expiry reminder: tenantId={}, membershipId={}, memberId={}, phone={}, expiryDate={}, daysRemaining={}",
					tenantId, membership.getId(), member.getId(), member.getPhone(), membership.getEndDate(), 3);

			queueAndDeliver(tenantId, member.getId(), membership.getId(), NotificationChannel.WHATSAPP,
					whatsappTemplate, member.getPhone(), variables);

			queued++;
		}

		log.info("Expiry reminder job completed: tenantId={}, expiryDate={}, queued={}", tenantId, expiryDate, queued);

		return queued;
	}

	@Transactional
	public int queueExpiryRemindersForAllTenants() {

		log.info("Starting expiry reminder job for all tenants");

		int total = 0;
		List<Tenant> tenants = tenantRepository.findAll();

		log.info("Tenants found for expiry reminder job: count={}", tenants.size());

		for (Tenant tenant : tenants) {
			log.info("Running expiry reminder job for tenant: tenantId={}, tenantName={}", tenant.getId(),
					tenant.getName());

			try {
				int queued = queueExpiryReminders(tenant.getId());
				total += queued;

				log.info("Tenant expiry reminder job completed: tenantId={}, queued={}", tenant.getId(), queued);

			} catch (Exception ex) {

				log.error("Expiry reminder job failed for tenant: tenantId={}, error={}", tenant.getId(),
						ex.getMessage(), ex);
			}
		}
		log.info("Expiry reminder job for all tenants completed: totalQueued={}", total);

		return total;
	}

	@Transactional
	public int retryFailedNotificationsForAllTenants() {

		log.info("Starting failed notification retry job for all tenants");

		int totalRetried = 0;
		List<Tenant> tenants = tenantRepository.findAll();

		for (Tenant tenant : tenants) {
			log.info("Retrying failed notifications for tenant: tenantId={}, tenantName={}", tenant.getId(),
					tenant.getName());
			try {
				int retried = retryFailedNotifications(tenant.getId());

				totalRetried += retried;

				log.info("Tenant notification retry completed: tenantId={}, retried={}", tenant.getId(), retried);
			} catch (Exception ex) {

				log.error("Tenant notification retry failed: tenantId={}, error={}", tenant.getId(), ex.getMessage(),
						ex);
			}
		}

		log.info("Failed notification retry job completed: totalRetried={}", totalRetried);

		return totalRetried;
	}

	@Transactional
	public int retryFailedNotifications(Long tenantId) {

		log.info("Starting failed notification retry: tenantId={}", tenantId);

		requireTenant(tenantId);

		int maxAttempts = 3;

		List<OutboundNotification> failed = outboundRepository.findByTenantIdAndStatusAndAttemptCountLessThan(tenantId,
				NotificationStatus.FAILED, maxAttempts);

		log.info("Failed notifications eligible for retry: tenantId={}, count={}, maxAttempts={}", tenantId,
				failed.size(), maxAttempts);

		int retried = 0;

		for (OutboundNotification notification : failed) {

			log.info(
					"Retrying notification: notificationId={}, tenantId={}, memberId={}, membershipId={}, attemptBeforeRetry={}",
					notification.getPublicId(), tenantId, notification.getMemberId(), notification.getMembershipId(),
					notification.getAttemptCount());

			deliver(notification);
			retried++;
		}

		log.info("Failed notification retry completed: tenantId={}, retried={}", tenantId, retried);

		return retried;
	}

	@Transactional(readOnly = true)
	public Page<OutboundNotification> outbound(Long tenantId, Pageable pageable) {
		log.info("Loading outbound notifications: tenantId={}, page={}, size={}", tenantId, pageable.getPageNumber(),
				pageable.getPageSize());

		requireTenant(tenantId);
		Page<OutboundNotification> result = outboundRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);

		log.info("Outbound notifications loaded: tenantId={}, page={}, size={}, totalElements={}, totalPages={}",
				tenantId, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());

		return result;
	}

	@Transactional
	public Announcement createAnnouncement(CreateAnnouncement request, Long tenantId) {
		log.info("Creating announcement: tenantId={}, title={}, published={}", tenantId, request.title(),
				request.published());
		requireTenant(tenantId);

		Announcement announcement = announcementRepository
				.save(new Announcement(tenantId, request.title(), request.body(), request.published()));

		log.info("Announcement saved: tenantId={}, announcementId={}, title={}", tenantId, announcement.getId(),
				announcement.getTitle());

		auditService.record(AuditActions.ANNOUNCEMENT_CREATED, AuditActions.RESULT_SUCCESS, "Announcement",
				announcement.getPublicId(), null);

		if (request.published()) {

			log.info("Announcement is published, starting WhatsApp delivery: tenantId={}, announcementId={}", tenantId,
					announcement.getId());

			queueAnnouncementWhatsApp(announcement, tenantId);
		} else {
			log.info("Announcement is not published, skipping WhatsApp delivery: tenantId={}, announcementId={}",
					tenantId, announcement.getId());
		}

		return announcement;
	}

	@Transactional
	protected int queueAnnouncementWhatsApp(Announcement announcement, Long tenantId) {

		log.info("Starting announcement WhatsApp delivery: tenantId={}, announcementId={}", tenantId,
				announcement.getId());

		List<Membership> memberships = membershipRepository.findByTenantIdAndStatus(tenantId, MembershipStatus.ACTIVE);

		log.info("Active memberships found for announcement: tenantId={}, announcementId={}, count={}", tenantId,
				announcement.getId(), memberships.size());

		int queued = 0;

		for (Membership membership : memberships) {

			log.debug(
					"Processing announcement membership: tenantId={}, announcementId={}, membershipId={}, memberId={}",
					tenantId, announcement.getId(), membership.getId(), membership.getMemberId());

			Member member = memberRepository.findById(membership.getMemberId()).orElse(null);

			if (member == null) {

				log.warn(
						"Skipping announcement because member not found: tenantId={}, announcementId={}, membershipId={}, memberId={}",
						tenantId, announcement.getId(), membership.getId(), membership.getMemberId());
				continue;
			}

			TenantGuard.check(member, tenantId, "Member");

			if (member.getPhone() == null || member.getPhone().isBlank()) {

				log.warn(
						"Skipping announcement because member has no phone: tenantId={}, announcementId={}, memberId={}",
						tenantId, announcement.getId(), member.getId());

				continue;
			}

			/*
			 * Prevent the same announcement from being queued twice for the same member.
			 */
			boolean alreadyQueued = outboundRepository.existsByTenantIdAndMemberIdAndAnnouncementIdAndChannel(tenantId,
					member.getId(), announcement.getId(), NotificationChannel.WHATSAPP);

			if (alreadyQueued) {
				log.debug("Skipping duplicate announcement notification: tenantId={}, announcementId={}, memberId={}",
						tenantId, announcement.getId(), member.getId());
				continue;
			}

			Map<String, Object> variables = Map.of("memberName", member.getFullName(), "memberCode",
					member.getMemberCode(), "gymName", gymDisplayName(tenantId));

			String body = templateRenderer.render(announcement.getBody(), variables);

			log.info(
					"Creating announcement outbound notification: tenantId={}, announcementId={}, memberId={}, recipient={}, whatsappTemplate={}, language={}",
					tenantId, announcement.getId(), member.getId(), member.getPhone(),
					WhatsAppTemplateDefaults.TEMPLATE_NAME, WhatsAppTemplateDefaults.LANGUAGE);

			OutboundNotification notification = new OutboundNotification(tenantId, member.getId(),
					NotificationChannel.WHATSAPP, NotificationTemplateKeys.ANNOUNCEMENT, member.getPhone(),
					announcement.getTitle(), body, WhatsAppTemplateDefaults.TEMPLATE_NAME,
					WhatsAppTemplateDefaults.LANGUAGE, null, // membershipId - announcement is not membership-specific
					announcement.getId());

			notification = outboundRepository.save(notification);

			log.info(
					"Announcement outbound notification saved: notificationId={}, tenantId={}, announcementId={}, memberId={}",
					notification.getPublicId(), tenantId, announcement.getId(), member.getId());

			deliver(notification);

			queued++;
		}
		log.info("Announcement WhatsApp delivery completed: tenantId={}, announcementId={}, queued={}", tenantId,
				announcement.getId(), queued);

		return queued;
	}

	@Transactional(readOnly = true)
	public List<Announcement> announcements(Long tenantId) {

		log.info("Loading announcements: tenantId={}", tenantId);

		requireTenant(tenantId);
		List<Announcement> announcements = announcementRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);

		log.info("Announcements loaded: tenantId={}, count={}", tenantId, announcements.size());

		return announcements;
	}

	private String recipientFor(Member member, NotificationChannel channel) {

		return switch (channel) {

		case EMAIL -> {

			if (member.getEmail() == null || member.getEmail().isBlank()) {
				log.warn("Member has no email: memberId={}", member.getId());
				throw CommonExceptions.badRequest("Member has no email");
			}

			yield member.getEmail();
		}

		case WHATSAPP -> {

			if (member.getPhone() == null || member.getPhone().isBlank()) {

				log.warn("Member has no phone for WhatsApp: memberId={}", member.getId());
				throw CommonExceptions.badRequest("Member has no phone");
			}

			yield member.getPhone();
		}

		case IN_APP -> member.getPublicId();
		};
	}

	private String gymDisplayName(Long tenantId) {

		if (tenantId == null) {
			log.warn("gymDisplayName called with null tenantId");
			return "Gym";
		}

		String displayName = profileRepository.findByTenantId(tenantId).map(p -> p.getDisplayName())
				.filter(name -> name != null && !name.isBlank())
				.or(() -> tenantRepository.findById(tenantId).map(t -> t.getName())).orElse("Gym");

		log.debug("Resolved gym display name: tenantId={}, displayName={}", tenantId, displayName);
		return displayName;
	}

	private void requireTenant(Long tenantId) {

		if (tenantId == null) {
			log.error("Tenant is required but tenantId is null");

			throw CommonExceptions.badRequest("A gym tenant is required");
		}
		log.debug("Tenant validated: tenantId={}", tenantId);
	}
}
