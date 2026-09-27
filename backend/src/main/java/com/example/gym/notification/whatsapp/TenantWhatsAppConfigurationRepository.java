package com.example.gym.notification.whatsapp;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantWhatsAppConfigurationRepository extends JpaRepository<TenantWhatsAppConfiguration, Long> {

	Optional<TenantWhatsAppConfiguration> findByTenantId(Long tenantId);

	Optional<TenantWhatsAppConfiguration> findByTenantIdAndActiveTrue(Long tenantId);

	boolean existsByTenantIdAndActiveTrue(Long tenantId);
}
