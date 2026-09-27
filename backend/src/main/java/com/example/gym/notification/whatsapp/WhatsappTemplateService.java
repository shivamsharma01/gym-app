package com.example.gym.notification.whatsapp;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.template.NotificationTemplate;
import com.example.gym.notification.template.NotificationTemplateVariable;
import com.example.gym.notification.template.NotificationTemplateVariableRepository;
import com.example.gym.notification.template.repository.NotificationTemplateRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WhatsappTemplateService {

	private final NotificationTemplateRepository templateRepository;

	private final NotificationTemplateVariableRepository variableRepository;

	private final WhatsappVariableResolver variableResolver;

	@Transactional(readOnly = true)
	public NotificationTemplate getTemplate(Long tenantId, String templateKey) {

		return templateRepository
				.findByTenantIdAndTemplateKeyAndChannelAndActiveTrue(tenantId, templateKey,
						NotificationChannel.WHATSAPP)
				.orElseThrow(() -> CommonExceptions.notFound("WhatsApp notification template"));
	}

	@Transactional(readOnly = true)
	public List<ResolvedWhatsappVariable> resolveVariables(NotificationTemplate template, NotificationContext context) {

		List<NotificationTemplateVariable> variables = variableRepository
				.findByNotificationTemplateIdOrderByVariableOrderAsc(template.getId());

		return variables.stream().map(variable -> {

			String value = variableResolver.resolve(variable.getVariableName(), context);

			return new ResolvedWhatsappVariable(variable.getVariableOrder(), variable.getVariableName(), value);
		}).toList();
	}
}
