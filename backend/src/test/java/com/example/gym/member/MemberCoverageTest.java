package com.example.gym.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberCoverageTest {

    @Test
    void cancelledFallbackDoesNotKeepTheMemberActiveWhenNothingCoversToday() {
        LocalDate today = LocalDate.now();
        Membership gold = membership("Gold", today.minusDays(29), today.minusDays(1), MembershipStatus.ACTIVE);
        Membership fallback = membership("Fallback", today.minusDays(10), today.plusDays(300), MembershipStatus.CANCELLED);
        Membership platinum = membership("Platinum", today.plusDays(1), today.plusDays(90), MembershipStatus.PENDING);

        assertThat(MemberCoverage.of(List.of(platinum, fallback, gold))).isEqualTo("PENDING");
        assertThat(MemberCoverage.of(List.of(fallback, gold))).isEqualTo("EXPIRED");
        assertThat(MemberCoverage.of(List.of(fallback))).isEqualTo("NO_PLAN");
    }

    @Test
    void aPlanCoveringTodayStaysActiveEvenWhenNothingHasBeenPaid() {
        LocalDate today = LocalDate.now();
        Membership current = membership("Gold", today.minusDays(1), today.plusDays(10), MembershipStatus.ACTIVE);

        assertThat(MemberCoverage.of(List.of(current))).isEqualTo("ACTIVE");
    }

    private static Membership membership(String name, LocalDate start, LocalDate end, MembershipStatus status) {
        return new Membership(1L, 1L, 1L, name, BigDecimal.TEN, "INR", start, end, status);
    }
}
