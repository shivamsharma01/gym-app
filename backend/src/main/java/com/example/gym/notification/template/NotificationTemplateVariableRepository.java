package com.example.gym.notification.template;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateVariableRepository extends JpaRepository<NotificationTemplateVariable, Long> {

	List<NotificationTemplateVariable> findByNotificationTemplateIdOrderByVariableOrderAsc(Long notificationTemplateId);
}
