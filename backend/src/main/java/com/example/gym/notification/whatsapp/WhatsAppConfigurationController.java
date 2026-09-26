package com.example.gym.notification.whatsapp;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.gym.security.SecurityUtils;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/whatsapp")
public class WhatsAppConfigurationController {

	private final TenantWhatsAppConfigurationService service;

	public WhatsAppConfigurationController(TenantWhatsAppConfigurationService service) {
		this.service = service;
	}

	@PutMapping("/configuration")
	@ResponseStatus(HttpStatus.OK)
	@PreAuthorize("hasAuthority('WHATSAPP_CONFIGURATION_MANAGE')")
	public TenantWhatsAppConfiguration configure(@Valid @RequestBody ConfigureWhatsAppAccount request) {

		return service.configure(SecurityUtils.currentTenantId(), request);
	}

	@GetMapping("/configuration")
	@PreAuthorize("hasAuthority('WHATSAPP_CONFIGURATION_MANAGE')")
	public ResponseEntity<WhatsAppConfigurationResponse> configuration() {

		return service.findActive(SecurityUtils.currentTenantId())
				.map(configuration -> ResponseEntity.ok(new WhatsAppConfigurationResponse(
						configuration.getId() != null ? configuration.getId().toString() : null,
						configuration.getPublicId(), configuration.getBusinessAccountId(),
						configuration.getPhoneNumberId(), configuration.getApiVersion(), configuration.isActive())))
				.orElseGet(() -> ResponseEntity
						.ok(new WhatsAppConfigurationResponse(null, null, null, null, "v25.0", false)));
	}

	@DeleteMapping("/configuration")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAuthority('WHATSAPP_CONFIGURATION_MANAGE')")
	public void disable() {

		service.disable(SecurityUtils.currentTenantId());
	}
}
