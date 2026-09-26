package com.example.gym.notification.outbound;

import com.example.gym.notification.whatsapp.WhatsappVariable;

public record OutboundParameterView(Integer order, WhatsappVariable variable, String value) {

	public static OutboundParameterView from(OutboundNotificationParameter parameter) {

		WhatsappVariable variable = null;

		if (parameter.getVariableName() != null && !parameter.getVariableName().isBlank()) {

			try {
				variable = WhatsappVariable.valueOf(parameter.getVariableName());
			} catch (IllegalArgumentException ex) {
				// Protect API serialization if an old/unknown
				// variable exists in the database.
				variable = null;
			}
		}

		return new OutboundParameterView(parameter.getParameterOrder(), variable, parameter.getParameterValue());
	}
}
