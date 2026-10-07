package com.example.gym.device;

import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.ReaderBlockedUser;
import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.DeviceUserSnapshotRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.ReaderBlockedUserRepository;
import com.example.gym.device.repo.ReaderRevisionRepository;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Next device-local id for one reader. The id is the next free integer above every id already
 * mapped, seen on the last roster snapshot, sitting on an unacked projection, or reported occupied.
 * It is not derived from a public id, member code, or serial number.
 */
@Service
public class DeviceUserIdAllocator {

    private final MemberDeviceMappingRepository mappings;
    private final DeviceUserSnapshotRepository snapshots;
    private final DesiredMemberProjectionRepository projections;
    private final ReaderRevisionRepository revisions;
    private final ReaderBlockedUserRepository blocked;

    public DeviceUserIdAllocator(MemberDeviceMappingRepository mappings,
                                 DeviceUserSnapshotRepository snapshots,
                                 DesiredMemberProjectionRepository projections,
                                 ReaderRevisionRepository revisions,
                                 ReaderBlockedUserRepository blocked) {
        this.mappings = mappings;
        this.snapshots = snapshots;
        this.projections = projections;
        this.revisions = revisions;
        this.blocked = blocked;
    }

    public String allocate(Long deviceId) {
        Set<String> taken = new HashSet<>();
        for (MemberDeviceMapping mapping : mappings.findByDeviceId(deviceId)) {
            add(taken, mapping.getDeviceUserId());
            add(taken, mapping.getPendingDeviceUserId());
        }
        snapshots.findByDeviceId(deviceId).forEach(row -> add(taken, row.getDeviceUserId()));
        long applied = revisions.findByDeviceId(deviceId).map(ReaderRevision::getAppliedRevision).orElse(0L);
        for (DesiredMemberProjection projection : projections.findByDeviceId(deviceId)) {
            if (projection.getRevision() > applied) {
                add(taken, projection.getDeviceUserId());
            }
        }
        for (ReaderBlockedUser row : blocked.findByDeviceId(deviceId)) {
            add(taken, row.getDeviceUserId());
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
