package com.example.gym.device;

import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Next device-local id for one reader. The id is the next free integer above every id already
 * mapped, seen on the last roster snapshot, sitting on an unacked projection, or reported occupied.
 * It is not derived from a public id, member code, or serial number. The caller must hold the
 * reader lock from {@link ReaderRevisions#lock}; otherwise two callers can pick the same id.
 */
@Service
public class DeviceUserIdAllocator {

    private final MemberDeviceMappingRepository mappings;

    public DeviceUserIdAllocator(MemberDeviceMappingRepository mappings) {
        this.mappings = mappings;
    }

    public String allocate(ReaderRevision lockedCursor) {
        Set<String> taken = new HashSet<>();
        for (String id : mappings.findTakenDeviceUserIds(lockedCursor.getDeviceId(), lockedCursor.getAppliedRevision())) {
            add(taken, id);
        }

        long highest = 0;
        for (String id : taken) {
            Long value = integer(id);
            if (value != null && value > highest) {
                highest = value;
            }
        }
        long candidate = highest + 1;
        while (taken.contains(Long.toString(candidate))) {
            candidate++;
        }
        return Long.toString(candidate);
    }

    private static void add(Set<String> taken, String id) {
        if (id != null && !id.isBlank()) {
            taken.add(id);
        }
    }

    /** Decimal text only. A public id, member code, or serial that is not an integer does not set H. */
    private static Long integer(String id) {
        if (id.length() > 18) {
            return null;
        }
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isDigit(id.charAt(i))) {
                return null;
            }
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
