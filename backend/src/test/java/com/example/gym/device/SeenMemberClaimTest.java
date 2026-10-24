package com.example.gym.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.member.DeviceAuthority;
import com.example.gym.member.Member;
import com.example.gym.member.MemberStatus;
import org.junit.jupiter.api.Test;

class SeenMemberClaimTest {

    @Test
    void theSameNameDatesAndAccessAreTheSamePerson() {
        Member member = member("Asha", "Rao");
        member.adoptReaderWindow("2026-01-01T00:00:00+05:30", "2027-03-31T23:59:59+05:30");

        assertThat(SeenMemberClaim.samePerson(member, observed("Asha Rao", 0, "2026-01-01T00:00:00.0000000+05:30",
                "2027-03-31T23:59:59.0000000+05:30"))).isTrue();
    }

    @Test
    void aDifferentNameOrDateStaysForReview() {
        Member member = member("Asha", "Rao");
        member.adoptReaderWindow("2026-01-01T00:00:00+05:30", "2027-03-31T23:59:59+05:30");

        assertThat(SeenMemberClaim.samePerson(member, observed("A Rao", 0, "2026-01-01T00:00:00+05:30",
                "2027-03-31T23:59:59+05:30"))).isFalse();
        assertThat(SeenMemberClaim.samePerson(member, observed("Asha Rao", 0, "2026-02-01T00:00:00+05:30",
                "2027-03-31T23:59:59+05:30"))).isFalse();
        assertThat(SeenMemberClaim.samePerson(member, observed("Asha Rao", 1, "2026-01-01T00:00:00+05:30",
                "2027-03-31T23:59:59+05:30"))).isFalse();
    }

    private static Member member(String first, String last) {
        Member member = new Member(1L, "MBR", first);
        member.setLastName(last);
        member.setStatus(MemberStatus.ACTIVE);
        member.setDeviceAuthority(DeviceAuthority.USER);
        return member;
    }

    private static DeviceObservedUser observed(String name, int status, String from, String to) {
        DeviceObservedUser observed = new DeviceObservedUser(1L, 2L, "11");
        observed.setReaderName(name);
        observed.setUserStatus(status);
        observed.setValidFrom(from);
        observed.setValidTo(to);
        observed.setAuthority("USER");
        return observed;
    }
}
