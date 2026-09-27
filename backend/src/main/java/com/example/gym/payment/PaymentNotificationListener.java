package com.example.gym.payment;

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
public class PaymentNotificationListener {

	private final MemberRepository memberRepository;
	private final PaymentRepository paymentRepository;
	private final NotificationService notificationService;

	public PaymentNotificationListener(MemberRepository memberRepository, PaymentRepository paymentRepository,
			NotificationService notificationService) {

		this.memberRepository = memberRepository;
		this.paymentRepository = paymentRepository;
		this.notificationService = notificationService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPaymentChanged(PaymentChangedEvent event) {

		log.info("Payment notification event received: tenantId={}, memberId={}, paymentId={}, type={}",
				event.tenantId(), event.memberId(), event.paymentId(), event.type());

		Payment payment = paymentRepository.findById(event.paymentId()).orElse(null);

		if (payment == null) {

			log.warn("Payment notification skipped: payment not found. " + "tenantId={}, paymentId={}",
					event.tenantId(), event.paymentId());

			return;
		}

		Member member = memberRepository.findById(event.memberId()).orElse(null);

		if (member == null) {

			log.warn("Payment notification skipped: member not found. " + "tenantId={}, memberId={}, paymentId={}",
					event.tenantId(), event.memberId(), event.paymentId());

			return;
		}

		/*
		 * Payment notifications are currently WhatsApp-based.
		 *
		 * NotificationService is responsible for:
		 *
		 * tenant -> application template -> configured WhatsApp variables ->
		 * WhatsappVariableResolver -> persisted OutboundNotificationParameter ->
		 * WhatsApp Cloud API
		 */

		if (!StringUtils.hasText(member.getPhone())) {

			log.warn(
					"Payment WhatsApp notification skipped: member has no phone. "
							+ "tenantId={}, memberId={}, paymentId={}",
					event.tenantId(), event.memberId(), event.paymentId());

			return;
		}

		String templateKey = resolveTemplateKey(event);

		log.info("Queueing payment WhatsApp notification: tenantId={}, " + "memberId={}, paymentId={}, templateKey={}",
				event.tenantId(), event.memberId(), event.paymentId(), templateKey);

		try {

			/*
			 * The third argument is the entity ID.
			 *
			 * For payments we pass payment.getId().
			 */
			notificationService.queueAndDeliver(event.tenantId(), event.memberId(), event.paymentId(),
					NotificationChannel.WHATSAPP, templateKey, member.getPhone());

			log.info(
					"Payment WhatsApp notification queued/delivered successfully: "
							+ "tenantId={}, memberId={}, paymentId={}, templateKey={}",
					event.tenantId(), event.memberId(), event.paymentId(), templateKey);

		} catch (Exception ex) {

			log.error(
					"Payment WhatsApp notification failed: tenantId={}, "
							+ "memberId={}, paymentId={}, templateKey={}, error={}",
					event.tenantId(), event.memberId(), event.paymentId(), templateKey, ex.getMessage(), ex);

			/*
			 * Do not rethrow.
			 *
			 * The payment transaction has already committed.
			 */
		}
	}

	private String resolveTemplateKey(PaymentChangedEvent event) {

		return switch (event.type()) {

		case RECORDED -> NotificationTemplateKeys.PAYMENT_RECORDED;

		case REFUNDED -> NotificationTemplateKeys.PAYMENT_REFUNDED;

		default -> throw new IllegalArgumentException("Unsupported payment event type: " + event.type());
		};
	}
}
