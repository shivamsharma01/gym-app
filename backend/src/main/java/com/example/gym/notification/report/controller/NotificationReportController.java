package com.example.gym.notification.report.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.gym.notification.dto.NotificationReportResponse;
import com.example.gym.notification.report.NotificationReportService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/reports/notifications")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('REPORT_VIEW')")
public class NotificationReportController {

	private final NotificationReportService service;

	@GetMapping
	public NotificationReportResponse report(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

			@RequestParam(required = false) String status,

			@RequestParam(required = false) String templateKey) {

		return service.getReport(from, to, status, templateKey);
	}
}
