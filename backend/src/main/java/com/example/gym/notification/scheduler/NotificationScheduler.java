package com.example.gym.notification.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.gym.notification.NotificationService;
import com.example.gym.security.SecurityUtils;

@Component
public class NotificationScheduler {

	private final NotificationService notificationService;

	public NotificationScheduler(NotificationService notificationService) {
		this.notificationService = notificationService;
	}

	/**
	 * Runs every day at 9:00 AM.
	 *
	 * Finds active memberships expiring exactly 3 days from today and sends the
	 * WhatsApp expiry reminder.
	 */
	@Scheduled(cron = "${notification.scheduler.expiry-cron:0 0 9 * * *}")
	public void processExpiryReminders() {
		notificationService.queueExpiryRemindersForAllTenants();
	}

	/**
	 * Runs every minute.
	 *
	 * Retries failed WhatsApp notifications that still have remaining attempts.
	 */
	@Scheduled(fixedDelayString = "${notification.scheduler.retry-delay-ms:60000}")
	public void retryFailedNotifications() {

        notificationService.retryFailedNotificationsForAllTenants();
	}
}
