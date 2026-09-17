package com.example.gym.notification.utils;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.gym.member.Member;
import com.example.gym.membership.Membership;
import com.example.gym.platform.PlatformTenantService;

@Component
public class NotificationVariableBuilder {

	private final PlatformTenantService tenantService;

	public NotificationVariableBuilder(PlatformTenantService tenantService) {

		this.tenantService = tenantService;
	}

	public Map<String, Object> membershipVariables(Member member, Membership membership) {

		LocalDate today = LocalDate.now();

		long daysRemaining = ChronoUnit.DAYS.between(today, membership.getEndDate());

		String fullName = Stream.of(member.getFirstName(), member.getLastName()).filter(StringUtils::hasText)
				.collect(Collectors.joining(" "));

		return Map.ofEntries(Map.entry("memberName", fullName),

				Map.entry("memberCode", member.getMemberCode()),

				Map.entry("gymName", tenantService.getDisplayName(membership.getTenantId())),

				Map.entry("membershipPlan", membership.getPlanName()),

				Map.entry("startDate", membership.getStartDate().toString()),

				Map.entry("expiryDate", membership.getEndDate().toString()),

				Map.entry("daysRemaining", Math.max(daysRemaining, 0)),

				Map.entry("amount", membership.getPrice()),

				Map.entry("currency", membership.getCurrency()),

				Map.entry("amountPaid", membership.getAmountPaid()),

				Map.entry("membershipStatus", membership.effectiveStatus(today).name()));
	}
}