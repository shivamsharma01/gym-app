package com.example.gym.notification.template;

import com.example.gym.notification.whatsapp.WhatsappVariable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_template_variable", uniqueConstraints = {
		@UniqueConstraint(name = "uk_template_variable_name", columnNames = { "notification_template_id",
				"variable_name" }),
		@UniqueConstraint(name = "uk_template_variable_order", columnNames = { "notification_template_id",
				"variable_order" }) })
@Getter
@Setter
@NoArgsConstructor
public class NotificationTemplateVariable {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "notification_template_id", nullable = false)
	private NotificationTemplate notificationTemplate;

	@Enumerated(EnumType.STRING)
	@Column(name = "variable_name", nullable = false, length = 100)
	private WhatsappVariable variableName;

	@Column(name = "variable_order", nullable = false)
	private Integer variableOrder;

	public NotificationTemplateVariable(NotificationTemplate notificationTemplate, WhatsappVariable variableName,
			Integer variableOrder) {

		this.notificationTemplate = notificationTemplate;
		this.variableName = variableName;
		this.variableOrder = variableOrder;
	}
}
