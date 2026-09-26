package com.example.gym.membership;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.notification.NotificationService;
import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.template.NotificationTemplateKeys;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class MembershipNotificationListener {

	private final MemberRepository memberRepository;
	private final MembershipRepository membershipRepository;
	private final NotificationService notificationService;

	public MembershipNotificationListener(MemberRepository memberRepository, MembershipRepository membershipRepository,
			NotificationService notificationService) {

		this.memberRepository = memberRepository;
		this.membershipRepository = membershipRepository;
		this.notificationService = notificationService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onMembershipChanged(MembershipChangedEvent event) {

		log.info("Membership notification event received: tenantId={}, memberId={}, membershipId={}, type={}",
				event.tenantId(), event.memberId(), event.membershipId(), event.type());

		Membership membership = membershipRepository.findById(event.membershipId()).orElse(null);

		if (membership == null) {
			log.warn("Membership notification skipped: membership not found. tenantId={}, membershipId={}",
					event.tenantId(), event.membershipId());
			return;
		}

		Member member = memberRepository.findById(event.memberId()).orElse(null);

		if (member == null) {
			log.warn("Membership notification skipped: member not found. tenantId={}, memberId={}, membershipId={}",
					event.tenantId(), event.memberId(), event.membershipId());
			return;
		}

		/*
		 * The membership notification is currently WhatsApp-based.
		 *
		 * We deliberately do NOT build a Map<String, Object> here.
		 *
		 * NotificationService will:
		 *
		 * tenant -> application template -> configured WhatsApp variables ->
		 * WhatsappVariableResolver -> persisted OutboundNotificationParameter ->
		 * WhatsApp Cloud API
		 */
		if (!StringUtils.hasText(member.getPhone())) {

			log.warn(
					"Membership WhatsApp notification skipped: member has no phone. "
							+ "tenantId={}, memberId={}, membershipId={}",
					event.tenantId(), event.memberId(), event.membershipId());

			return;
		}

		String templateKey = resolveTemplateKey(event);

		log.info(
				"Queueing membership WhatsApp notification: tenantId={}, memberId={}, "
						+ "membershipId={}, templateKey={}",
				event.tenantId(), event.memberId(), event.membershipId(), templateKey);

		try {

			notificationService.queueAndDeliver(event.tenantId(), event.memberId(), event.membershipId(),
					NotificationChannel.WHATSAPP, templateKey, member.getPhone());

			log.info(
					"Membership WhatsApp notification queued/delivered successfully: "
							+ "tenantId={}, memberId={}, membershipId={}, templateKey={}",
					event.tenantId(), event.memberId(), event.membershipId(), templateKey);

		} catch (Exception ex) {

			log.error(
					"Membership WhatsApp notification failed: tenantId={}, memberId={}, "
							+ "membershipId={}, templateKey={}, error={}",
					event.tenantId(), event.memberId(), event.membershipId(), templateKey, ex.getMessage(), ex);

			/*
			 * Do not rethrow here unless you intentionally want an AFTER_COMMIT listener
			 * failure to propagate to the event infrastructure.
			 *
			 * The original membership transaction has already committed.
			 */
		}
	}

	private String resolveTemplateKey(MembershipChangedEvent event) {

		return switch (event.type()) {

		case CREATED -> NotificationTemplateKeys.MEMBERSHIP_CREATED;

		case RENEWED -> NotificationTemplateKeys.MEMBERSHIP_RENEWED;

		case FROZEN -> NotificationTemplateKeys.MEMBERSHIP_FROZEN;

		case UNFROZEN -> NotificationTemplateKeys.MEMBERSHIP_UNFROZEN;

		case CANCELLED -> NotificationTemplateKeys.MEMBERSHIP_CANCELLED;

		case DATES_UPDATED -> NotificationTemplateKeys.MEMBERSHIP_DATES_UPDATED;

		case DELETED ->
			throw new IllegalArgumentException("Membership notification is not supported for DELETED events");

		default -> throw new IllegalArgumentException("Unsupported membership event type: " + event.type());
		};
	}
}
