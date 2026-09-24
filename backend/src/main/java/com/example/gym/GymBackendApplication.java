package com.example.gym;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Smart Gym Management Platform — backend API.
 *
 * <p>This service is the authoritative business source of truth (members, memberships,
 * payments, access status, devices, audit). It never links against the native device SDK;
 * all device integration happens through the Device Gateway (see docs/device-sdk.md).
 */
@SpringBootApplication
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class GymBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(GymBackendApplication.class, args);
    }
}
