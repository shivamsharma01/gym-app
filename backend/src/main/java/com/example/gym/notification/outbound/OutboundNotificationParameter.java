package com.example.gym.notification.outbound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "outbound_notification_parameter", uniqueConstraints = {
		@UniqueConstraint(name = "uk_outbound_notification_parameter_order", columnNames = { "outbound_notification_id",
				"parameter_order" }) })
@Getter
@NoArgsConstructor
public class OutboundNotificationParameter {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "outbound_notification_id", nullable = false)
	private OutboundNotification notification;

	@Column(name = "parameter_order", nullable = false)
	private Integer parameterOrder;

	@Column(name = "variable_name", nullable = false, length = 100)
	private String variableName;

	@Column(name = "parameter_value", columnDefinition = "TEXT")
	private String parameterValue;

	public OutboundNotificationParameter(OutboundNotification notification, Integer parameterOrder, String variableName,
			String parameterValue) {

		this.notification = notification;
		this.parameterOrder = parameterOrder;
		this.variableName = variableName;
		this.parameterValue = parameterValue;
	}

	public Long getId() {
		return id;
	}

	public OutboundNotification getNotification() {
		return notification;
	}

	public Integer getParameterOrder() {
		return parameterOrder;
	}

	public String getParameterValue() {
		return parameterValue;
	}

	public String getVariableName() {
		return variableName;
	}

	public void setNotification(OutboundNotification notification) {
		this.notification = notification;
	}

	public void setParameterOrder(Integer parameterOrder) {
		this.parameterOrder = parameterOrder;
	}

	public void setParameterValue(String parameterValue) {
		this.parameterValue = parameterValue;
	}

	public void setVariableName(String variableName) {
		this.variableName = variableName;
	}
}
