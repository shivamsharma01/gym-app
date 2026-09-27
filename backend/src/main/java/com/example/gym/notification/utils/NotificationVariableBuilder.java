//package com.example.gym.notification.utils;
//
//import java.time.LocalDate;
//import java.time.temporal.ChronoUnit;
//
//import org.springframework.stereotype.Component;
//
//import com.example.gym.member.Member;
//import com.example.gym.membership.Membership;
//import com.example.gym.notification.whatsapp.NotificationContext;
//import com.example.gym.platform.PlatformTenantService;
//
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//
//@Slf4j
//@Component
//@RequiredArgsConstructor
//public class NotificationVariableBuilder {
//
//	private final PlatformTenantService tenantService;
//
//	public NotificationContext membershipContext(Member member, Membership membership) {
//
//		LocalDate today = LocalDate.now();
//
//		long days = ChronoUnit.DAYS.between(today, membership.getEndDate());
//
//		return new NotificationContext(member, membership, tenantService.getDisplayName(membership.getTenantId()),
//				(int) Math.max(days, 0));
//	}
//}
