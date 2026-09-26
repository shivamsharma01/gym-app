package com.example.gym.notification.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.gym.notification.dto.NotificationReportResponse;
import com.example.gym.notification.dto.NotificationReportResponse.DailySummary;
import com.example.gym.notification.dto.NotificationReportResponse.NotificationRow;
import com.example.gym.notification.dto.NotificationReportResponse.Rates;
import com.example.gym.notification.dto.NotificationReportResponse.Summary;
import com.example.gym.notification.dto.NotificationReportResponse.TemplateSummary;
import com.example.gym.notification.outbound.OutboundNotification;
import com.example.gym.notification.outbound.repository.OutboundNotificationRepository;
import com.example.gym.notification.utils.NotificationStatus;
import com.example.gym.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationReportService {

	private final OutboundNotificationRepository repository;

	public NotificationReportResponse getReport(LocalDate from, LocalDate to, String status, String templateKey) {

		Long tenantId = SecurityUtils.currentTenantId();

		/*
		 * Use the server's configured timezone when converting LocalDate to Instant.
		 */
		ZoneId zone = ZoneId.systemDefault();

		/*
		 * from is inclusive.
		 */
		Instant fromInstant = from.atStartOfDay(zone).toInstant();

		/*
		 * to is inclusive, therefore query until the beginning of the following day.
		 */
		Instant toExclusive = to.plusDays(1).atStartOfDay(zone).toInstant();

		/*
		 * Load notification records belonging to this tenant and date range.
		 */
		List<OutboundNotification> rows = repository.findReportRows(tenantId, fromInstant, toExclusive);

		/*
		 * Apply optional filters.
		 */
		List<OutboundNotification> filtered = rows.stream().filter(row -> matchesStatus(row, status))
				.filter(row -> matchesTemplate(row, templateKey)).toList();

		/*
		 * Overall summary.
		 */
		Summary summary = buildSummary(filtered);

		/*
		 * Delivery/read/failure percentages.
		 */
		Rates rates = buildRates(summary);

		/*
		 * Group by notification template.
		 */
		List<TemplateSummary> byTemplate = buildTemplateSummary(filtered);

		/*
		 * Group by calendar day.
		 */
		List<DailySummary> daily = buildDailySummary(filtered, zone);

		/*
		 * Detailed notification rows.
		 */
		List<NotificationRow> notifications = filtered.stream().map(this::toNotificationRow).toList();

		return new NotificationReportResponse(summary, rates, byTemplate, daily, notifications);
	}

	/**
	 * Check notification status filter.
	 */
	private boolean matchesStatus(OutboundNotification row, String status) {

		if (status == null || status.isBlank()) {
			return true;
		}

		if ("ALL".equalsIgnoreCase(status)) {
			return true;
		}

		NotificationStatus rowStatus = row.getStatus();

		if (rowStatus == null) {
			return false;
		}

		return rowStatus.name().equalsIgnoreCase(status.trim());
	}

	/**
	 * Check notification template filter.
	 */
	private boolean matchesTemplate(OutboundNotification row, String templateKey) {

		if (templateKey == null || templateKey.isBlank()) {
			return true;
		}

		if ("ALL".equalsIgnoreCase(templateKey)) {
			return true;
		}

		String rowTemplateKey = row.getTemplateKey();

		if (rowTemplateKey == null) {
			return false;
		}

		return rowTemplateKey.equalsIgnoreCase(templateKey.trim());
	}

	/**
	 * Build overall notification statistics.
	 */
	private Summary buildSummary(List<OutboundNotification> rows) {

		long total = rows.size();

		long sent = rows.stream().filter(this::isSent).count();

		long delivered = rows.stream().filter(this::isDelivered).count();

		long read = rows.stream().filter(this::isRead).count();

		long failed = rows.stream().filter(this::isFailed).count();

		long pending = rows.stream().filter(this::isPending).count();

		return new Summary(total, sent, delivered, read, failed, pending);
	}

	/**
	 * Build notification rates.
	 *
	 * Delivery rate: delivered / sent
	 *
	 * Read rate: read / delivered
	 *
	 * Failure rate: failed / total
	 */
	private Rates buildRates(Summary summary) {

		BigDecimal deliveryRate = percentage(summary.delivered(), summary.sent());

		BigDecimal readRate = percentage(summary.read(), summary.delivered());

		BigDecimal failureRate = percentage(summary.failed(), summary.total());

		return new Rates(deliveryRate, readRate, failureRate);
	}

	/**
	 * Convert two numbers into a percentage.
	 */
	private BigDecimal percentage(long numerator, long denominator) {

		if (denominator <= 0) {
			return BigDecimal.ZERO;
		}

		return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator),
				2, RoundingMode.HALF_UP);
	}

	/**
	 * Build notification statistics grouped by template.
	 */
	private List<TemplateSummary> buildTemplateSummary(List<OutboundNotification> rows) {

		Map<String, List<OutboundNotification>> grouped = rows.stream().collect(Collectors.groupingBy(row -> {

			String key = row.getTemplateKey();

			if (key == null || key.isBlank()) {
				return "UNKNOWN";
			}

			return key;
		}, LinkedHashMap::new, Collectors.toList()));

		return grouped.entrySet().stream().map(entry -> {

			String template = entry.getKey();

			List<OutboundNotification> items = entry.getValue();

			long total = items.size();

			long sent = items.stream().filter(this::isSent).count();

			long delivered = items.stream().filter(this::isDelivered).count();

			long read = items.stream().filter(this::isRead).count();

			long failed = items.stream().filter(this::isFailed).count();

			long pending = items.stream().filter(this::isPending).count();

			return new TemplateSummary(template, total, sent, delivered, read, failed, pending);
		}).sorted(Comparator.comparingLong(TemplateSummary::total).reversed()).toList();
	}

	/**
	 * Build notification statistics grouped by day.
	 */
	private List<DailySummary> buildDailySummary(List<OutboundNotification> rows, ZoneId zone) {

		Map<LocalDate, List<OutboundNotification>> grouped = rows.stream().collect(Collectors.groupingBy(
				row -> row.getCreatedAt().atZone(zone).toLocalDate(), LinkedHashMap::new, Collectors.toList()));

		return grouped.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {

			LocalDate date = entry.getKey();

			List<OutboundNotification> items = entry.getValue();

			long total = items.size();

			long sent = items.stream().filter(this::isSent).count();

			long delivered = items.stream().filter(this::isDelivered).count();

			long read = items.stream().filter(this::isRead).count();

			long failed = items.stream().filter(this::isFailed).count();

			long pending = items.stream().filter(this::isPending).count();

			return new DailySummary(date, total, sent, delivered, read, failed, pending);
		}).toList();
	}

	/**
	 * Convert entity into API response row.
	 */
	private NotificationRow toNotificationRow(OutboundNotification row) {

		return new NotificationRow(String.valueOf(row.getId()), row.getMemberId(), row.getMembershipId(),
				row.getRecipient(), row.getChannel(), row.getTemplateKey(), row.getWhatsappTemplateName(),
				row.getWhatsappLanguage(), row.getStatus(), row.getAttemptCount(), row.getLastError(),
				row.getScheduledAt(), row.getSentAt(), row.getDeliveredAt(), row.getReadAt(), row.getCreatedAt());
	}

	/**
	 * A notification is considered sent when it reached SENT, DELIVERED, or READ.
	 */
	private boolean isSent(OutboundNotification row) {

		NotificationStatus status = row.getStatus();

		return status == NotificationStatus.SENT || status == NotificationStatus.DELIVERED
				|| status == NotificationStatus.READ;
	}

	/**
	 * A notification is considered delivered when:
	 *
	 * 1. deliveredAt exists, OR 2. status is DELIVERED, OR 3. status is READ.
	 */
	private boolean isDelivered(OutboundNotification row) {

		if (row.getDeliveredAt() != null) {
			return true;
		}

		NotificationStatus status = row.getStatus();

		return status == NotificationStatus.DELIVERED || status == NotificationStatus.READ;
	}

	/**
	 * A notification is considered read when:
	 *
	 * 1. readAt exists, OR 2. status is READ.
	 */
	private boolean isRead(OutboundNotification row) {

		if (row.getReadAt() != null) {
			return true;
		}

		return row.getStatus() == NotificationStatus.READ;
	}

	/**
	 * A notification is considered failed when its status is FAILED.
	 */
	private boolean isFailed(OutboundNotification row) {

		return row.getStatus() == NotificationStatus.FAILED;
	}

	/**
	 * A notification is considered pending when it is still queued.
	 *
	 * Your enum currently contains:
	 *
	 * QUEUED SENT DELIVERED READ FAILED
	 *
	 * Therefore there is no PENDING or PROCESSING status to check here.
	 */
	private boolean isPending(OutboundNotification row) {

		return row.getStatus() == NotificationStatus.QUEUED;
	}
}
