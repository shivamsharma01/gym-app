package com.example.gym.notification.whatsapp;

import org.springframework.stereotype.Component;

import com.example.gym.membership.Membership;

@Component
public class WhatsappVariableResolver {

	public String resolve(WhatsappVariable variable, NotificationContext context) {

		if (variable == null) {
			throw new IllegalArgumentException("WhatsApp variable cannot be null");
		}

		if (context == null) {
			throw new IllegalArgumentException("Notification context cannot be null");
		}

		return switch (variable) {

		case MEMBER_NAME -> value(context.member().getFullName());

		case MEMBER_CODE -> value(context.member().getMemberCode());

		case EMAIL -> value(context.member().getEmail());

		case PHONE -> value(context.member().getPhone());

		case GYM_NAME -> value(context.gymName());

		case MEMBERSHIP_PLAN -> requireMembership(context).getPlanName();

		case START_DATE -> requireMembership(context).getStartDate().toString();

		case EXPIRY_DATE -> requireMembership(context).getEndDate().toString();

		case DAYS_REMAINING -> context.daysRemaining() == null ? "" : String.valueOf(context.daysRemaining());

		case AMOUNT -> String.valueOf(requireMembership(context).getPrice());

		case CURRENCY -> value(requireMembership(context).getCurrency());

		case AMOUNT_PAID -> String.valueOf(requireMembership(context).getAmountPaid());

		case MEMBERSHIP_STATUS -> requireMembership(context).getStatus().name();

		/*
		 * Do not use AGE here unless your Member entity actually has getAge().
		 */
		case AGE -> "";
		};
	}

	private Membership requireMembership(NotificationContext context) {

		if (context.membership() == null) {

			throw new IllegalArgumentException("Membership is required for WhatsApp variable");
		}

		return context.membership();
	}

	private String value(String value) {

		return value == null ? "" : value;
	}
}
