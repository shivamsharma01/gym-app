package com.example.gym.access;

import com.example.gym.member.Member;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * The validity window copied from a reader onto the member. Account active is a separate flag and
 * does not follow these dates.
 */
public final class ReaderValidity {

    public enum State {
        /** No reader dates are stored. */
        NONE,
        /** Today is inside the stored window. */
        OPEN,
        /** Today is after the stored end date. */
        EXPIRED,
        /** Today is before the stored start date. */
        NOT_STARTED
    }

    private ReaderValidity() {
    }

    public static State on(Member member, LocalDate today) {
        LocalDate from = day(member.getAdoptedValidFrom());
        LocalDate to = day(member.getAdoptedValidTo());
        if (from == null || to == null) {
            from = member.getDeviceValidFrom();
            to = member.getDeviceValidTo();
        }
        if (from == null || to == null) {
            return State.NONE;
        }
        if (today.isBefore(from)) {
            return State.NOT_STARTED;
        }
        if (today.isAfter(to)) {
            return State.EXPIRED;
        }
        return State.OPEN;
    }

    private static LocalDate day(String readerTime) {
        if (readerTime == null || readerTime.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(readerTime).toLocalDate();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(readerTime.length() >= 10 ? readerTime.substring(0, 10) : readerTime);
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }
}
