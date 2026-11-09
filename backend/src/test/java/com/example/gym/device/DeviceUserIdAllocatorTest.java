package com.example.gym.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeviceUserIdAllocatorTest {

    @Mock
    MemberDeviceMappingRepository mappings;

    @InjectMocks
    DeviceUserIdAllocator allocator;

    @Test
    void firstIdIsOneWhenNothingIsTaken() {
        ReaderRevision cursor = cursor(10L, 0L);
        when(mappings.findTakenDeviceUserIds(10L, 0L)).thenReturn(List.of());

        assertThat(allocator.allocate(cursor)).isEqualTo("1");
    }

    @Test
    void growsPastTheHighestTakenId() {
        ReaderRevision cursor = cursor(10L, 5L);
        when(mappings.findTakenDeviceUserIds(10L, 5L)).thenReturn(List.of("1", "2", "4", "9"));

        assertThat(allocator.allocate(cursor)).isEqualTo("10");
    }

    @Test
    void reusesAClearedHighIdWhenItIsNoLongerTaken() {
        ReaderRevision cursor = cursor(10L, 0L);
        when(mappings.findTakenDeviceUserIds(10L, 0L)).thenReturn(List.of("1", "2", "3"));

        assertThat(allocator.allocate(cursor)).isEqualTo("4");
    }

    private static ReaderRevision cursor(long deviceId, long applied) {
        ReaderRevision cursor = new ReaderRevision(1L, deviceId);
        cursor.setAppliedRevision(applied);
        return cursor;
    }
}
