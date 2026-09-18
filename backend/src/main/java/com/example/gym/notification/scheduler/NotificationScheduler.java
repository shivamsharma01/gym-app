package com.example.gym.notification.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.gym.notification.NotificationService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
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
		log.info("Starting scheduled expiry reminder job");

		try {
			int queued = notificationService.queueExpiryRemindersForAllTenants();

			log.info("Scheduled expiry reminder job completed: queued={}", queued);
		} catch (Exception e) {
			log.error("Scheduled expiry reminder job failed", e);
		}
	}

	/**
	 * Runs every minute.
	 *
	 * Retries failed WhatsApp notifications that still have remaining attempts.
	 */
	@Scheduled(fixedDelayString = "${notification.scheduler.retry-delay-ms:60000}")
	public void retryFailedNotifications() {

		log.info("Starting scheduled notification retry job");

		try {
			int retried = notificationService.retryFailedNotificationsForAllTenants();

			log.info("Scheduled notification retry job completed: retried={}", retried);
		} catch (Exception e) {
			log.error("Scheduled notification retry job failed", e);
		}
	}
}
