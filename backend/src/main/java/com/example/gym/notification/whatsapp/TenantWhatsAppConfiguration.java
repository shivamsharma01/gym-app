package com.example.gym.notification.whatsapp;

import com.example.gym.common.domain.TenantAwareEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "tenant_whatsapp_configuration")
@Getter
@Setter
@NoArgsConstructor
public class TenantWhatsAppConfiguration extends TenantAwareEntity {

	@Column(name = "business_account_id", length = 100)
	private String businessAccountId;

	@Column(name = "phone_number_id", nullable = false, length = 100)
	private String phoneNumberId;

	@Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
	private String accessToken;

	@Column(name = "api_version", nullable = false, length = 30)
	private String apiVersion;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	public TenantWhatsAppConfiguration(Long tenantId, String businessAccountId, String phoneNumberId,
			String accessToken, String apiVersion) {

		setTenantId(tenantId);

		this.businessAccountId = businessAccountId;
		this.phoneNumberId = phoneNumberId;
		this.accessToken = accessToken;
		this.apiVersion = apiVersion;
		this.active = true;
	}
}
