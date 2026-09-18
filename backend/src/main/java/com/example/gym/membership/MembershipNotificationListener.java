package com.example.gym.membership;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.notification.NotificationService;
import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.template.NotificationTemplateKeys;
import com.example.gym.notification.utils.NotificationVariableBuilder;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class MembershipNotificationListener {

	private final MemberRepository memberRepository;
	private final MembershipRepository membershipRepository;
	private final NotificationService notificationService;
	private final NotificationVariableBuilder variableBuilder;

	public MembershipNotificationListener(MemberRepository memberRepository, MembershipRepository membershipRepository,
			NotificationService notificationService, NotificationVariableBuilder variableBuilder) {

		this.memberRepository = memberRepository;
		this.membershipRepository = membershipRepository;
		this.notificationService = notificationService;
		this.variableBuilder = variableBuilder;
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

		log.debug("Membership found for notification: membershipId={}, memberId={}, status={}", membership.getId(),
				membership.getMemberId(), membership.getStatus());

		Member member = memberRepository.findById(event.memberId()).orElse(null);

		if (member == null) {

			log.warn("Membership notification skipped: member not found. tenantId={}, memberId={}, membershipId={}",
					event.tenantId(), event.memberId(), event.membershipId());
			return;
		}

		log.debug("Member found for membership notification: memberId={}, membershipId={}, hasPhone={}, hasEmail={}",
				member.getId(), membership.getId(), StringUtils.hasText(member.getPhone()),
				StringUtils.hasText(member.getEmail()));

		Map<String, Object> variables = variableBuilder.membershipVariables(member, membership);

		log.debug("Membership notification variables built: tenantId={}, memberId={}, membershipId={}, variableKeys={}",
				event.tenantId(), event.memberId(), event.membershipId(), variables.keySet());

		String templateKey = switch (event.type()) {

		case CREATED -> NotificationTemplateKeys.MEMBERSHIP_CREATED;

		case RENEWED -> NotificationTemplateKeys.MEMBERSHIP_RENEWED;

		case FROZEN -> NotificationTemplateKeys.MEMBERSHIP_FROZEN;

		case UNFROZEN -> NotificationTemplateKeys.MEMBERSHIP_UNFROZEN;

		case CANCELLED -> NotificationTemplateKeys.MEMBERSHIP_CANCELLED;

		case DATES_UPDATED -> NotificationTemplateKeys.MEMBERSHIP_DATES_UPDATED;
		};

		log.info(
				"Membership notification template selected: tenantId={}, memberId={}, membershipId={}, eventType={}, templateKey={}",
				event.tenantId(), event.memberId(), event.membershipId(), event.type(), templateKey);

		send(event, member, templateKey, variables);
	}

	private void send(MembershipChangedEvent event, Member member, String templateKey, Map<String, Object> variables) {

		if (!StringUtils.hasText(member.getPhone())) {

			log.warn(
					"Membership WhatsApp notification skipped: member has no phone. tenantId={}, memberId={}, membershipId={}, templateKey={}",
					event.tenantId(), event.memberId(), event.membershipId(), templateKey);

			return;
		}

		log.info("Queueing membership WhatsApp notification: tenantId={}, memberId={}, membershipId={}, templateKey={}",
				event.tenantId(), event.memberId(), event.membershipId(), templateKey);

		try {

			notificationService.queueAndDeliver(event.tenantId(), event.memberId(), event.membershipId(),
					NotificationChannel.WHATSAPP, templateKey, member.getPhone(), variables);

			log.info(
					"Membership WhatsApp notification queued/delivered successfully: tenantId={}, memberId={}, membershipId={}, templateKey={}",
					event.tenantId(), event.memberId(), event.membershipId(), templateKey);

		} catch (Exception ex) {

			log.error(
					"Membership WhatsApp notification failed: tenantId={}, memberId={}, membershipId={}, templateKey={}, error={}",
					event.tenantId(), event.memberId(), event.membershipId(), templateKey, ex.getMessage(), ex);

			throw ex;
		}
	}
}