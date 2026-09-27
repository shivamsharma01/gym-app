package com.example.gym.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
import com.example.gym.notification.outbound.OutboundNotificationParameter;
import com.example.gym.notification.outbound.repository.OutboundNotificationRepository;
import com.example.gym.notification.template.NotificationTemplate;
import com.example.gym.notification.template.NotificationTemplateKeys;
import com.example.gym.notification.template.NotificationTemplateVariable;
import com.example.gym.notification.template.NotificationTemplateVariableRepository;
import com.example.gym.notification.template.TemplateRenderer;
import com.example.gym.notification.template.repository.NotificationTemplateRepository;
import com.example.gym.notification.utils.NotificationStatus;
import com.example.gym.notification.whatsapp.ConfigureWhatsappTemplate;
import com.example.gym.notification.whatsapp.NotificationContext;
import com.example.gym.notification.whatsapp.WhatsappTemplateService;
import com.example.gym.notification.whatsapp.WhatsappVariable;
import com.example.gym.notification.whatsapp.WhatsappVariableRequest;
import com.example.gym.notification.whatsapp.WhatsappVariableResolver;
import com.example.gym.settings.GymProfileRepository;
import com.example.gym.tenant.Tenant;
import com.example.gym.tenant.TenantGuard;
import com.example.gym.tenant.TenantRepository;
import com.example.gym.tenant.TenantStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class NotificationService {

	private final int maxRetryAttempts;
	private final int batchSize;

	/*
	 * Notification delivery window.
	 *
	 * Example:
	 *
	 * 08:00 - 21:00 -> notification may be sent immediately 21:01 -> queued for
	 * next day 08:00 02:00 -> queued for same day 08:00
	 */
	private final LocalTime notificationStartTime;
	private final LocalTime notificationEndTime;

	/*
	 * Used to prevent the first deployment of the new notification system from
	 * immediately sending historical expiry reminders.
	 *
	 * This should normally be configured explicitly in application.properties.
	 */
	private final LocalDate expiryNotificationActivationDate;

	private final NotificationTemplateRepository templateRepository;
	private final NotificationTemplateVariableRepository templateVariableRepository;
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
	private final WhatsappVariableResolver whatsappVariableResolver;
	private final WhatsappTemplateService whatsappTemplateService;

	public NotificationService(NotificationTemplateRepository templateRepository,
			NotificationTemplateVariableRepository templateVariableRepository,
			OutboundNotificationRepository outboundRepository, AnnouncementRepository announcementRepository,
			MemberService memberService, MemberRepository memberRepository, MembershipRepository membershipRepository,
			List<NotificationChannelAdapter> adapterList, AuditService auditService, TenantRepository tenantRepository,
			GymProfileRepository profileRepository, TemplateRenderer templateRenderer,
			WhatsappVariableResolver whatsappVariableResolver, WhatsappTemplateService whatsappTemplateService,
			Environment environment) {

		this.templateRepository = templateRepository;
		this.templateVariableRepository = templateVariableRepository;
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
		this.whatsappVariableResolver = whatsappVariableResolver;
		this.whatsappTemplateService = whatsappTemplateService;

		this.maxRetryAttempts = environment.getProperty("notification.scheduler.max-retry-attempts", Integer.class, 3);

		this.batchSize = environment.getProperty("notification.scheduler.batch-size", Integer.class, 50);

		this.notificationStartTime = environment.getProperty("notification.delivery.start-time", LocalTime.class,
				LocalTime.of(8, 0));

		this.notificationEndTime = environment.getProperty("notification.delivery.end-time", LocalTime.class,
				LocalTime.of(21, 0));

		this.expiryNotificationActivationDate = environment.getProperty("notification.expiry.activation-date",
				LocalDate.class, LocalDate.now());
	}

	// =========================================================
	// TEMPLATE MANAGEMENT
	// =========================================================

	@Transactional(readOnly = true)
	public List<NotificationTemplate> templates(Long tenantId) {

		requireTenant(tenantId);

		return templateRepository.findByTenantIdOrderByTemplateKeyAsc(tenantId);
	}

	@Transactional
	public NotificationTemplate configureWhatsappTemplate(ConfigureWhatsappTemplate request, Long tenantId) {

		requireTenant(tenantId);
		validateWhatsappTemplateRequest(request);

		NotificationTemplate template = templateRepository
				.findByTenantIdAndTemplateKeyAndChannel(tenantId, request.templateKey(), NotificationChannel.WHATSAPP)
				.orElseGet(() -> new NotificationTemplate(tenantId, request.templateKey(), NotificationChannel.WHATSAPP,
						null, null));

		template.setWhatsappTemplateName(request.whatsappTemplateName().trim());

		template.setWhatsappLanguage(request.whatsappLanguage().trim());

		template.setActive(true);

		template.getWhatsappVariables().clear();

		for (WhatsappVariableRequest variable : request.variables()) {
			template.addWhatsappVariable(variable.variable(), variable.order());
		}

		NotificationTemplate saved = templateRepository.save(template);

		log.info(
				"WhatsApp template configured: tenantId={}, templateId={}, "
						+ "templateKey={}, metaTemplate={}, language={}, variableCount={}",
				tenantId, saved.getPublicId(), saved.getTemplateKey(), saved.getWhatsappTemplateName(),
				saved.getWhatsappLanguage(), request.variables().size());

		return saved;
	}

	@Transactional
	public NotificationTemplate upsertTemplate(UpsertTemplate request, Long tenantId) {

		requireTenant(tenantId);

		if (request == null) {
			throw CommonExceptions.badRequest("Notification template request is required");
		}

		if (!StringUtils.hasText(request.templateKey())) {
			throw CommonExceptions.badRequest("Template key is required");
		}

		if (request.channel() == null) {
			throw CommonExceptions.badRequest("Notification channel is required");
		}

		NotificationTemplate template = templateRepository
				.findByTenantIdAndTemplateKeyAndChannel(tenantId, request.templateKey(), request.channel())
				.orElseGet(() -> new NotificationTemplate(tenantId, request.templateKey(), request.channel(),
						request.subject(), request.body()));

		template.setSubject(request.subject());
		template.setBody(request.body());
		template.setActive(true);

		if (request.channel() != NotificationChannel.WHATSAPP) {
			template.setWhatsappTemplateName(null);
			template.setWhatsappLanguage(null);
			template.getWhatsappVariables().clear();
		}

		return templateRepository.save(template);
	}

	// =========================================================
	// MANUAL SEND
	// =========================================================

	@Transactional
	public OutboundNotification send(SendNotification request, Long tenantId) {

		requireTenant(tenantId);

		if (request == null) {
			throw CommonExceptions.badRequest("Notification request is required");
		}

		Member member = memberService.getByPublicId(request.memberId(), tenantId);

		NotificationTemplate template = templateRepository
				.findByTenantIdAndTemplateKeyAndChannelAndActiveTrue(tenantId, request.templateKey(), request.channel())
				.orElseThrow(() -> CommonExceptions.notFound("Notification template"));

		String recipient = recipientFor(member, request.channel());

		Membership membership = null;

		if (StringUtils.hasText(request.membershipId())) {

			membership = membershipRepository.findByPublicId(request.membershipId())
					.orElseThrow(() -> CommonExceptions.notFound("Membership"));

			TenantGuard.check(membership.getTenantId(), tenantId, "Membership");

			if (!membership.getMemberId().equals(member.getId())) {
				throw CommonExceptions.badRequest("Membership does not belong to the specified member");
			}
		}

		NotificationContext context = buildContext(tenantId, member, membership,
				membership == null ? null : calculateDaysRemaining(membership));

		return queueAndDeliver(tenantId, member, membership, null, request.channel(), template, recipient, context);
	}

	// =========================================================
	// MEMBERSHIP NOTIFICATIONS
	// =========================================================

	@Transactional
	public OutboundNotification sendMembershipNotification(Long tenantId, Membership membership, String templateKey) {

		requireTenant(tenantId);

		if (membership == null) {
			throw CommonExceptions.badRequest("Membership is required");
		}

		TenantGuard.check(membership.getTenantId(), tenantId, "Membership");

		Member member = memberRepository.findById(membership.getMemberId())
				.orElseThrow(() -> CommonExceptions.notFound("Member"));

		TenantGuard.check(member, tenantId, "Member");

		NotificationTemplate template = whatsappTemplateService.getTemplate(tenantId, templateKey);

		if (!StringUtils.hasText(member.getPhone())) {
			throw CommonExceptions.badRequest("Member has no phone");
		}

		NotificationContext context = buildContext(tenantId, member, membership, calculateDaysRemaining(membership));

		return queueAndDeliver(tenantId, member, membership, null, NotificationChannel.WHATSAPP, template,
				member.getPhone(), context);
	}

	private NotificationContext buildContext(Long tenantId, Member member, Membership membership,
			Integer daysRemaining) {

		return new NotificationContext(member, membership, gymDisplayName(tenantId), daysRemaining);
	}

	// =========================================================
	// QUEUE + DELIVERY
	// =========================================================

	/**
	 * Convenience method for application code that already has IDs.
	 */
	@Transactional
	public OutboundNotification queueAndDeliver(Long tenantId, Long memberId, Long membershipId,
			NotificationChannel channel, String templateKey, String recipient) {

		requireTenant(tenantId);

		NotificationTemplate template = templateRepository
				.findByTenantIdAndTemplateKeyAndChannelAndActiveTrue(tenantId, templateKey, channel)
				.orElseThrow(() -> CommonExceptions.notFound("Notification template"));

		Member member = memberRepository.findById(memberId).orElseThrow(() -> CommonExceptions.notFound("Member"));

		TenantGuard.check(member, tenantId, "Member");

		Membership membership = null;

		if (membershipId != null) {

			membership = membershipRepository.findById(membershipId)
					.orElseThrow(() -> CommonExceptions.notFound("Membership"));

			TenantGuard.check(membership.getTenantId(), tenantId, "Membership");

			if (!membership.getMemberId().equals(member.getId())) {
				throw CommonExceptions.badRequest("Membership does not belong to the specified member");
			}
		}

		NotificationContext context = buildContext(tenantId, member, membership,
				membership == null ? null : calculateDaysRemaining(membership));

		return queueAndDeliver(tenantId, member, membership, null, channel, template, recipient, context);
	}

	/**
	 * Creates the immutable outbound notification snapshot.
	 *
	 * Delivery policy:
	 *
	 * 08:00 - 21:00 -> send immediately
	 *
	 * Before 08:00 -> queue for today 08:00
	 *
	 * After 21:00 -> queue for tomorrow 08:00
	 *
	 * The notification itself is always persisted first.
	 */
	private OutboundNotification queueAndDeliver(Long tenantId, Member member, Membership membership,
			Announcement announcement, NotificationChannel channel, NotificationTemplate template, String recipient,
			NotificationContext context) {

		if (!StringUtils.hasText(recipient)) {
			throw CommonExceptions.badRequest("Notification recipient is required");
		}

		Map<String, Object> genericVariables = buildGenericVariables(context);

		String subject = templateRenderer.render(template.getSubject(), genericVariables);

		String body = templateRenderer.render(template.getBody(), genericVariables);

		OutboundNotification notification = new OutboundNotification(tenantId, member.getId(), channel,
				template.getTemplateKey(), recipient, subject, body, template.getWhatsappTemplateName(),
				template.getWhatsappLanguage(), membership == null ? null : membership.getId(),
				announcement == null ? null : announcement.getId());

		LocalDateTime now = LocalDateTime.now();

		LocalDateTime scheduledAt = calculateScheduledAt(now);

		notification.setScheduledAt(scheduledAt);

		/*
		 * Save first.
		 *
		 * This guarantees that the notification exists in the database even if the
		 * provider subsequently fails.
		 */
		notification = outboundRepository.save(notification);

		/*
		 * Persist WhatsApp parameters once.
		 *
		 * Retry never recalculates business values.
		 */
		if (channel == NotificationChannel.WHATSAPP) {

			persistWhatsappParameters(notification, template, context);
		}

		/*
		 * If the notification is currently inside the allowed delivery window, send
		 * immediately.
		 *
		 * Otherwise it remains QUEUED.
		 */
		if (!scheduledAt.isAfter(now)) {

			deliver(notification);

		} else {

			log.info(
					"Notification scheduled: notificationId={}, " + "tenantId={}, memberId={}, membershipId={}, "
							+ "channel={}, templateKey={}, scheduledAt={}",
					notification.getPublicId(), tenantId, member.getId(),
					membership == null ? null : membership.getId(), channel, template.getTemplateKey(), scheduledAt);
		}

		return notification;
	}

	/**
	 * Calculates the first permitted delivery time.
	 */
	private LocalDateTime calculateScheduledAt(LocalDateTime now) {

		LocalTime currentTime = now.toLocalTime();

		/*
		 * Normal delivery window.
		 *
		 * Example: 10:30 -> now
		 */
		if (!currentTime.isBefore(notificationStartTime) && currentTime.isBefore(notificationEndTime)) {

			return now;
		}

		/*
		 * Before the morning window.
		 *
		 * Example: 06:30 -> today 08:00
		 */
		if (currentTime.isBefore(notificationStartTime)) {

			return LocalDateTime.of(now.toLocalDate(), notificationStartTime);
		}

		/*
		 * After the evening window.
		 *
		 * Example: 23:30 -> tomorrow 08:00
		 */
		return LocalDateTime.of(now.toLocalDate().plusDays(1), notificationStartTime);
	}

	private void persistWhatsappParameters(OutboundNotification notification, NotificationTemplate template,
			NotificationContext context) {

		List<NotificationTemplateVariable> configuredVariables = templateVariableRepository
				.findByNotificationTemplateIdOrderByVariableOrderAsc(template.getId());

		if (configuredVariables.isEmpty()) {

			log.debug("WhatsApp template has no variables: " + "notificationId={}, templateId={}",
					notification.getPublicId(), template.getPublicId());

			return;
		}

		for (NotificationTemplateVariable variable : configuredVariables) {

			String value = whatsappVariableResolver.resolve(variable.getVariableName(), context);

			OutboundNotificationParameter parameter = new OutboundNotificationParameter();

			parameter.setNotification(notification);
			parameter.setParameterOrder(variable.getVariableOrder());
			parameter.setVariableName(variable.getVariableName().name());
			parameter.setParameterValue(value);

			notification.addParameter(parameter);
		}

		outboundRepository.save(notification);

		log.debug("WhatsApp parameters persisted: notificationId={}, " + "parameterCount={}",
				notification.getPublicId(), configuredVariables.size());
	}

	// =========================================================
	// DELIVERY
	// =========================================================

	/**
	 * Attempts delivery through the configured channel adapter.
	 *
	 * Provider exceptions are converted to FAILED.
	 *
	 * The scheduler can subsequently retry the notification.
	 */
	private void deliver(OutboundNotification notification) {

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

			log.error("Notification delivery failed: " + "notificationId={}, channel={}, attempt={}, error={}",
					notification.getPublicId(), notification.getChannel(), notification.getAttemptCount(), message, ex);

			markFailed(notification, message);
		}

		outboundRepository.save(notification);
	}

	private void markFailed(OutboundNotification notification, String error) {

		notification.setStatus(NotificationStatus.FAILED);

		notification.setLastError(error);
	}

	// =========================================================
	// EXPIRY REMINDERS
	// =========================================================

	@Transactional
	public int queueExpiryReminders(Long tenantId) {

		requireTenant(tenantId);

		LocalDate today = LocalDate.now();

		LocalDate expiryDate = today.plusDays(3);

		String templateKey = NotificationTemplateKeys.EXPIRY_REMINDER_3_DAYS;

		NotificationTemplate template = templateRepository.findByTenantIdAndTemplateKeyAndChannelAndActiveTrue(tenantId,
				templateKey, NotificationChannel.WHATSAPP).orElse(null);

		if (template == null) {

			log.warn("Expiry reminder template not configured: " + "tenantId={}, templateKey={}", tenantId,
					templateKey);

			return 0;
		}

		/*
		 * If the notification system has just been enabled, don't send historical
		 * expiry reminders automatically.
		 *
		 * The activation date is a safety switch for the initial data migration.
		 */
		if (today.isBefore(expiryNotificationActivationDate)) {

			log.info("Expiry reminder scheduler not active yet: " + "tenantId={}, today={}, activationDate={}",
					tenantId, today, expiryNotificationActivationDate);

			return 0;
		}

		List<Membership> memberships = membershipRepository.findByTenantIdAndEndDateAndStatus(tenantId, expiryDate,
				MembershipStatus.ACTIVE);

		int queued = 0;

		for (Membership membership : memberships) {

			/*
			 * Deleted memberships should never receive an expiry notification.
			 */
			if (membership.isDeleted()) {
				continue;
			}

			Member member = memberRepository.findById(membership.getMemberId()).orElse(null);

			if (member == null) {
				continue;
			}

			TenantGuard.check(member, tenantId, "Member");

			if (!StringUtils.hasText(member.getPhone())) {

				log.debug("Skipping expiry reminder because member " + "has no phone: memberId={}", member.getId());

				continue;
			}

			/*
			 * Idempotency.
			 *
			 * Scheduler may run more than once.
			 *
			 * Only one expiry notification is allowed for a membership/template/channel
			 * combination.
			 */
			boolean alreadyQueued = outboundRepository.existsByMembershipIdAndTemplateKeyAndChannel(membership.getId(),
					templateKey, NotificationChannel.WHATSAPP);

			if (alreadyQueued) {
				continue;
			}

			NotificationContext context = buildContext(tenantId, member, membership, 3);

			queueAndDeliver(tenantId, member, membership, null, NotificationChannel.WHATSAPP, template,
					member.getPhone(), context);

			queued++;
		}

		log.info("Expiry reminders processed: " + "tenantId={}, expiryDate={}, queued={}", tenantId, expiryDate,
				queued);

		return queued;
	}

	@Transactional
	public int queueExpiryRemindersForAllTenants() {

		int total = 0;

		List<Tenant> tenants = tenantRepository.findByStatus(TenantStatus.ACTIVE);

		for (Tenant tenant : tenants) {

			try {

				total += queueExpiryReminders(tenant.getId());

			} catch (Exception ex) {

				log.error("Expiry reminder failed: tenantId={}", tenant.getId(), ex);
			}
		}

		return total;
	}

	// =========================================================
	// QUEUED NOTIFICATION PROCESSOR
	// =========================================================

	/**
	 * Processes notifications whose scheduledAt has arrived.
	 *
	 * This handles:
	 *
	 * - payment notifications created at night - membership notifications created
	 * at night - expiry notifications created outside the delivery window - any
	 * future notification type using queueAndDeliver()
	 */
	@Transactional
	public int processQueuedNotificationsForAllTenants() {

		LocalDateTime now = LocalDateTime.now();

		List<OutboundNotification> notifications = outboundRepository
				.findTop100ByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(NotificationStatus.QUEUED, now);

		int processed = 0;

		for (OutboundNotification notification : notifications) {

			try {

				/*
				 * Defensive check.
				 */
				if (notification.getScheduledAt() == null) {
					log.warn("Queued notification has no scheduledAt: " + "notificationId={}",
							notification.getPublicId());

					continue;
				}

				if (notification.getScheduledAt().isAfter(now)) {
					continue;
				}

				/*
				 * Only deliver within the configured delivery window.
				 *
				 * This protects against a scheduler configuration that runs at an unexpected
				 * time.
				 */
				LocalTime currentTime = now.toLocalTime();

				if (currentTime.isBefore(notificationStartTime) || !currentTime.isBefore(notificationEndTime)) {

					continue;
				}

				/*
				 * Deliver using the persisted notification and persisted WhatsApp parameters.
				 */
				deliver(notification);

				processed++;

			} catch (Exception ex) {

				log.error("Queued notification delivery failed: " + "notificationId={}", notification.getPublicId(),
						ex);
			}
		}

		return processed;
	}

	// =========================================================
	// RETRY
	// =========================================================

	@Transactional
	public int retryFailedNotifications(Long tenantId) {

		requireTenant(tenantId);

		Pageable pageable = PageRequest.of(0, batchSize);

		List<OutboundNotification> failed = outboundRepository.findByTenantIdAndStatusAndAttemptCountLessThan(tenantId,
				NotificationStatus.FAILED, maxRetryAttempts, pageable);

		int retried = 0;

		for (OutboundNotification notification : failed) {

			/*
			 * Don't retry outside the notification window.
			 *
			 * A failed notification at 23:00 should wait until the next permitted delivery
			 * window.
			 */
			LocalTime currentTime = LocalTime.now();

			if (currentTime.isBefore(notificationStartTime) || !currentTime.isBefore(notificationEndTime)) {

				continue;
			}

			/*
			 * deliver() uses the existing OutboundNotification and existing WhatsApp
			 * parameters.
			 *
			 * It does NOT recalculate business data.
			 */
			deliver(notification);

			retried++;
		}

		return retried;
	}

	@Transactional
	public int retryFailedNotificationsForAllTenants() {

		int total = 0;

		List<Tenant> tenants = tenantRepository.findByStatus(TenantStatus.ACTIVE);

		for (Tenant tenant : tenants) {

			try {

				total += retryFailedNotifications(tenant.getId());

			} catch (Exception ex) {

				log.error("Notification retry failed: tenantId={}", tenant.getId(), ex);
			}
		}

		return total;
	}

	// =========================================================
	// ANNOUNCEMENTS
	// =========================================================

	@Transactional
	public Announcement createAnnouncement(CreateAnnouncement request, Long tenantId) {

		requireTenant(tenantId);

		if (request == null) {
			throw CommonExceptions.badRequest("Announcement request is required");
		}

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

		NotificationTemplate template = templateRepository.findByTenantIdAndTemplateKeyAndChannelAndActiveTrue(tenantId,
				NotificationTemplateKeys.ANNOUNCEMENT, NotificationChannel.WHATSAPP).orElse(null);

		if (template == null) {

			log.warn("Announcement WhatsApp template not configured: " + "tenantId={}", tenantId);

			return 0;
		}

		int queued = 0;

		for (Membership membership : memberships) {

			Member member = memberRepository.findById(membership.getMemberId()).orElse(null);

			if (member == null) {
				continue;
			}

			TenantGuard.check(member, tenantId, "Member");

			if (!StringUtils.hasText(member.getPhone())) {

				continue;
			}

			boolean alreadyQueued = outboundRepository.existsByTenantIdAndMemberIdAndAnnouncementIdAndChannel(tenantId,
					member.getId(), announcement.getId(), NotificationChannel.WHATSAPP);

			if (alreadyQueued) {
				continue;
			}

			NotificationContext context = buildContext(tenantId, member, null, null);

			queueAndDeliver(tenantId, member, null, announcement, NotificationChannel.WHATSAPP, template,
					member.getPhone(), context);

			queued++;
		}

		log.info("Announcement WhatsApp notifications queued: " + "tenantId={}, announcementId={}, queued={}", tenantId,
				announcement.getPublicId(), queued);

		return queued;
	}

	@Transactional(readOnly = true)
	public List<Announcement> announcements(Long tenantId) {

		requireTenant(tenantId);

		return announcementRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
	}

	// =========================================================
	// OUTBOUND
	// =========================================================

	@Transactional(readOnly = true)
	public Page<OutboundNotification> outbound(Long tenantId, Pageable pageable) {

		requireTenant(tenantId);

		return outboundRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
	}

	// =========================================================
	// GENERIC TEMPLATE VARIABLES
	// =========================================================

	private Map<String, Object> buildGenericVariables(NotificationContext context) {

		Member member = context.member();

		Membership membership = context.membership();

		return Map.ofEntries(

				Map.entry("memberName", value(member == null ? null : member.getFullName())),

				Map.entry("memberCode", value(member == null ? null : member.getMemberCode())),

				Map.entry("gymName", value(context.gymName())),

				Map.entry("membershipPlan", membership == null ? "" : value(membership.getPlanName())),

				Map.entry("startDate",
						membership == null || membership.getStartDate() == null ? ""
								: membership.getStartDate().toString()),

				Map.entry("expiryDate",
						membership == null || membership.getEndDate() == null ? ""
								: membership.getEndDate().toString()),

				Map.entry("daysRemaining", context.daysRemaining() == null ? "" : context.daysRemaining()),

				Map.entry("amount",
						membership == null || membership.getPrice() == null ? "" : membership.getPrice().toString()),

				Map.entry("currency", membership == null ? "" : value(membership.getCurrency())),

				Map.entry("amountPaid",
						membership == null || membership.getAmountPaid() == null ? ""
								: membership.getAmountPaid().toString()),

				Map.entry("membershipStatus", membership == null ? "" : membership.getStatus().name()));
	}

	// =========================================================
	// HELPERS
	// =========================================================

	private int calculateDaysRemaining(Membership membership) {

		if (membership == null || membership.getEndDate() == null) {

			return 0;
		}

		long days = ChronoUnit.DAYS.between(LocalDate.now(), membership.getEndDate());

		return (int) Math.max(days, 0);
	}

	private String recipientFor(Member member, NotificationChannel channel) {

		if (channel == null) {
			throw CommonExceptions.badRequest("Notification channel is required");
		}

		return switch (channel) {

		case EMAIL -> {

			if (!StringUtils.hasText(member.getEmail())) {

				throw CommonExceptions.badRequest("Member has no email");
			}

			yield member.getEmail().trim();
		}

		case WHATSAPP -> {

			if (!StringUtils.hasText(member.getPhone())) {

				throw CommonExceptions.badRequest("Member has no phone");
			}

			yield member.getPhone().trim();
		}

		case IN_APP -> member.getPublicId();
		};
	}

	private String gymDisplayName(Long tenantId) {

		return profileRepository.findByTenantId(tenantId).map(profile -> profile.getDisplayName())
				.filter(StringUtils::hasText).or(() -> tenantRepository.findById(tenantId).map(Tenant::getName))
				.orElse("Gym");
	}

	private void validateWhatsappTemplateRequest(ConfigureWhatsappTemplate request) {

		if (request == null) {
			throw CommonExceptions.badRequest("WhatsApp template configuration is required");
		}

		if (!StringUtils.hasText(request.templateKey())) {

			throw CommonExceptions.badRequest("Template key is required");
		}

		if (!StringUtils.hasText(request.whatsappTemplateName())) {

			throw CommonExceptions.badRequest("WhatsApp template name is required");
		}

		if (!StringUtils.hasText(request.whatsappLanguage())) {

			throw CommonExceptions.badRequest("WhatsApp template language is required");
		}

		if (request.variables() == null) {

			throw CommonExceptions.badRequest("WhatsApp variables are required");
		}

		Set<Integer> orders = new HashSet<>();

		Set<WhatsappVariable> variables = new HashSet<>();

		for (WhatsappVariableRequest variable : request.variables()) {

			if (variable == null || variable.variable() == null) {

				throw CommonExceptions.badRequest("WhatsApp variable cannot be null");
			}

			if (variable.order() == null || variable.order() <= 0) {

				throw CommonExceptions.badRequest("WhatsApp variable order must be greater than zero");
			}

			if (!orders.add(variable.order())) {

				throw CommonExceptions.badRequest("Duplicate WhatsApp variable order: " + variable.order());
			}

			if (!variables.add(variable.variable())) {

				throw CommonExceptions.badRequest("Duplicate WhatsApp variable: " + variable.variable());
			}
		}
	}

	private String value(String value) {

		return value == null ? "" : value;
	}

	private void requireTenant(Long tenantId) {

		if (tenantId == null) {

			throw CommonExceptions.badRequest("A gym tenant is required");
		}
	}
}
