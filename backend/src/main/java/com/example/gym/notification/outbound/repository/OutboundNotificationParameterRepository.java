package com.example.gym.notification.outbound.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.gym.notification.outbound.OutboundNotificationParameter;

public interface OutboundNotificationParameterRepository extends JpaRepository<OutboundNotificationParameter, Long> {

	List<OutboundNotificationParameter> findByNotificationIdOrderByParameterOrderAsc(Long notificationId);
}
