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

		Membership membership = membershipRepository.findById(event.membershipId()).orElse(null);

		if (membership == null) {
			return;
		}

		Member member = memberRepository.findById(event.memberId()).orElse(null);

		if (member == null) {
			return;
		}

		Map<String, Object> variables = variableBuilder.membershipVariables(member, membership);

		String templateKey = switch (event.type()) {

		case CREATED -> NotificationTemplateKeys.MEMBERSHIP_CREATED;

		case RENEWED -> NotificationTemplateKeys.MEMBERSHIP_RENEWED;

		case FROZEN -> NotificationTemplateKeys.MEMBERSHIP_FROZEN;

		case UNFROZEN -> NotificationTemplateKeys.MEMBERSHIP_UNFROZEN;

		case CANCELLED -> NotificationTemplateKeys.MEMBERSHIP_CANCELLED;

		case DATES_UPDATED -> NotificationTemplateKeys.MEMBERSHIP_DATES_UPDATED;
		};

		send(event, member, templateKey, variables);
	}

	private void send(MembershipChangedEvent event, Member member, String templateKey, Map<String, Object> variables) {

//		if (StringUtils.hasText(member.getEmail())) {
//
//			notificationService.queueAndDeliver(event.tenantId(), event.memberId(), NotificationChannel.EMAIL,
//					templateKey, member.getEmail(), variables);
//		}

		if (StringUtils.hasText(member.getPhone())) {

			notificationService.queueAndDeliver(event.tenantId(), event.memberId(),event.membershipId(), NotificationChannel.WHATSAPP,
					templateKey, member.getPhone(), variables);
		}
	}
}