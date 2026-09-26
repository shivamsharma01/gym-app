package com.example.gym.notification.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.utils.NotificationStatus;

public record NotificationReportResponse(Summary summary, Rates rates, List<TemplateSummary> byTemplate,
		List<DailySummary> daily, List<NotificationRow> notifications) {

	public record Summary(long total, long sent, long delivered, long read, long failed, long pending) {
	}

	public record Rates(BigDecimal deliveryRate, BigDecimal readRate, BigDecimal failureRate) {
	}

	public record TemplateSummary(String templateKey, long total, long sent, long delivered, long read, long failed,
			long pending) {
	}

	public record DailySummary(LocalDate date, long total, long sent, long delivered, long read, long failed,
			long pending) {
	}

	public record NotificationRow(String id, Long memberId, Long membershipId, String recipient,
			NotificationChannel channel, String templateKey, String whatsappTemplateName, String whatsappLanguage,
			NotificationStatus status, int attemptCount, String lastError, LocalDateTime scheduledAt, Instant sentAt,
			Instant deliveredAt, Instant readAt, Instant createdAt) {
	}

}
