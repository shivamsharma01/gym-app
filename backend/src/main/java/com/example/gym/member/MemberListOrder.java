package com.example.gym.member;

import com.example.gym.member.MemberCoverage.Window;
import com.example.gym.member.dto.MemberResponse.ReaderUserId;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;

/** Sort keys the member list can ask for. Reader ids, plan status, and validity are not member columns. */
public final class MemberListOrder {

    private MemberListOrder() {
    }

    public static boolean inDatabase(String sort) {
        if (sort == null || sort.isBlank() || "name".equalsIgnoreCase(sort)) {
            return true;
        }
        return "authority".equalsIgnoreCase(sort) || "account".equalsIgnoreCase(sort);
    }

    public static Sort databaseSort(String sort, String direction) {
        Sort.Direction dir = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        if ("name".equalsIgnoreCase(sort)) {
            return Sort.by(new Sort.Order(dir, "firstName"), new Sort.Order(dir, "lastName"), Sort.Order.asc("id"));
        }
        if ("authority".equalsIgnoreCase(sort)) {
            return Sort.by(new Sort.Order(dir, "deviceAuthority"), Sort.Order.asc("id"));
        }
        if ("account".equalsIgnoreCase(sort)) {
            return Sort.by(new Sort.Order(dir, "status"), Sort.Order.asc("id"));
        }
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    public static Comparator<Member> comparator(String sort, boolean descending, String readerId,
                                                Map<Long, Window> windows,
                                                Map<Long, List<ReaderUserId>> readers) {
        Comparator<Member> order = switch (sort == null ? "" : sort.toLowerCase()) {
            case "coverage" -> comparingMissingLast(
                    member -> rank(window(windows, member).status()), descending);
            case "validto" -> comparingMissingLast(member -> window(windows, member).validTo(), descending);
            case "reader" -> comparingMissingLast(member -> readerNumber(readers.get(member.getId()), readerId), descending);
            case "authority" -> comparingMissingLast(
                    member -> member.getDeviceAuthority() == null ? null : member.getDeviceAuthority().name(), descending);
            case "account" -> comparingMissingLast(
                    member -> member.getStatus() == null ? null : member.getStatus().name(), descending);
            default -> Comparator.comparing(Member::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        };
        return order.thenComparing(Member::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Window window(Map<Long, Window> windows, Member member) {
        Window window = windows.get(member.getId());
        return window == null ? new Window("NO_PLAN", null, null) : window;
    }

    private static int rank(String status) {
        return switch (status == null ? "" : status) {
            case "ACTIVE" -> 0;
            case "PENDING" -> 1;
            case "FROZEN" -> 2;
            case "EXPIRED" -> 3;
            case "CANCELLED" -> 4;
            default -> 5;
        };
    }

    private static Long readerNumber(List<ReaderUserId> readers, String deviceId) {
        if (readers == null || deviceId == null) {
            return null;
        }
        for (ReaderUserId reader : readers) {
            if (deviceId.equals(reader.deviceId()) && reader.deviceUserId() != null && !reader.deviceUserId().isBlank()) {
                try {
                    return Long.parseLong(reader.deviceUserId().trim());
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    private static <T extends Comparable<? super T>> Comparator<Member> comparingMissingLast(
            java.util.function.Function<Member, T> value, boolean descending) {
        return (left, right) -> {
            T a = value.apply(left);
            T b = value.apply(right);
            if (a == null && b == null) {
                return 0;
            }
            if (a == null) {
                return 1;
            }
            if (b == null) {
                return -1;
            }
            int compared = a.compareTo(b);
            return descending ? -compared : compared;
        };
    }
}
