package com.example.gym.notification.whatsapp;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.tenant.TenantGuard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantWhatsAppConfigurationService {

	private final TenantWhatsAppConfigurationRepository repository;
	private final WhatsAppProperties properties;

	@Transactional
	public TenantWhatsAppConfiguration configure(Long tenantId, ConfigureWhatsAppAccount request) {

		requireTenant(tenantId);

		if (request == null) {
			throw CommonExceptions.badRequest("WhatsApp account configuration is required");
		}

		validate(request);

		String apiVersion = resolveApiVersion(request.apiVersion());

		TenantWhatsAppConfiguration configuration = repository.findByTenantId(tenantId)
				.orElseGet(() -> new TenantWhatsAppConfiguration(tenantId, request.businessAccountId(),
						request.phoneNumberId(), request.accessToken(), apiVersion));

		TenantGuard.check(configuration.getTenantId(), tenantId, "WhatsApp configuration");

		configuration.setBusinessAccountId(request.businessAccountId());

		configuration.setPhoneNumberId(request.phoneNumberId());

		configuration.setAccessToken(request.accessToken());

		configuration.setApiVersion(apiVersion);

		configuration.setActive(true);

		TenantWhatsAppConfiguration saved = repository.save(configuration);

		log.info("WhatsApp account configured: tenantId={}, phoneNumberId={}, businessAccountId={}", tenantId,
				saved.getPhoneNumberId(), saved.getBusinessAccountId());

		return saved;
	}

	@Transactional(readOnly = true)
	public TenantWhatsAppConfiguration getRequired(Long tenantId) {

		requireTenant(tenantId);

		return repository.findByTenantIdAndActiveTrue(tenantId)
				.orElseThrow(() -> CommonExceptions.notFound("WhatsApp account configuration"));
	}
	
	public Optional<TenantWhatsAppConfiguration> findActive(Long tenantId) {
	    requireTenant(tenantId);

	    return repository.findByTenantIdAndActiveTrue(tenantId);
	}


	@Transactional(readOnly = true)
	public TenantWhatsAppConfiguration getOptional(Long tenantId) {

		requireTenant(tenantId);

		return repository.findByTenantId(tenantId).orElse(null);
	}

	@Transactional
	public void disable(Long tenantId) {

		requireTenant(tenantId);

		TenantWhatsAppConfiguration configuration = repository.findByTenantId(tenantId)
				.orElseThrow(() -> CommonExceptions.notFound("WhatsApp account configuration"));

		configuration.setActive(false);

		repository.save(configuration);

		log.info("WhatsApp account disabled: tenantId={}", tenantId);
	}

	private String resolveApiVersion(String version) {

		if (StringUtils.hasText(version)) {
			return version.trim();
		}

		if (!StringUtils.hasText(properties.getDefaultApiVersion())) {
			throw new IllegalStateException("whatsapp.default-api-version is not configured");
		}

		return properties.getDefaultApiVersion().trim();
	}

	private void validate(ConfigureWhatsAppAccount request) {

		if (!StringUtils.hasText(request.businessAccountId())) {
			throw CommonExceptions.badRequest("WhatsApp Business Account ID is required");
		}

		if (!StringUtils.hasText(request.phoneNumberId())) {
			throw CommonExceptions.badRequest("WhatsApp phone number ID is required");
		}

		if (!StringUtils.hasText(request.accessToken())) {
			throw CommonExceptions.badRequest("WhatsApp access token is required");
		}
	}

	private void requireTenant(Long tenantId) {

		if (tenantId == null) {
			throw CommonExceptions.badRequest("A gym tenant is required");
		}
	}
}
