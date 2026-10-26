package com.example.gym.device;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.repo.AttendanceEventRepository;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.face.MemberFaceRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

@ExtendWith(MockitoExtension.class)
class PunchMemberResolverTest {

    @Mock MemberDeviceMappingRepository mappings;
    @Mock DeviceObservedUserRepository observedUsers;
    @Mock MemberFaceRepository faces;
    @Mock AttendanceEventRepository attendance;
    @Mock Device device;

    PunchMemberResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PunchMemberResolver(mappings, observedUsers, faces, attendance);
        when(device.getId()).thenReturn(7L);
        when(device.getTenantId()).thenReturn(1L);
        when(device.getPublicId()).thenReturn("reader-pub");
    }

    @Test
    void a_deadlock_while_linking_punches_does_not_propagate() {
        MemberDeviceMapping mapping = new MemberDeviceMapping(1L, 99L, 7L, "42");
        when(mappings.findByDeviceIdAndDeviceUserId(7L, "42")).thenReturn(Optional.of(mapping));
        doThrow(new CannotAcquireLockException("Deadlock found when trying to get lock"))
                .when(attendance).linkUnmatched(anyLong(), anyLong(), anyString(), anyLong());

        assertThatCode(() -> resolver.creditEarlierPunches(device, "42")).doesNotThrowAnyException();
        verify(attendance).linkUnmatched(1L, 7L, "42", 99L);
    }

    @Test
    void a_lock_wait_message_wrapped_in_runtime_does_not_propagate() {
        MemberDeviceMapping mapping = new MemberDeviceMapping(1L, 99L, 7L, "42");
        when(mappings.findByDeviceIdAndDeviceUserId(7L, "42")).thenReturn(Optional.of(mapping));
        doThrow(new RuntimeException(new RuntimeException("Lock wait timeout exceeded; try restarting transaction")))
                .when(attendance).linkUnmatched(anyLong(), anyLong(), anyString(), anyLong());

        assertThatCode(() -> resolver.creditEarlierPunches(device, "42")).doesNotThrowAnyException();
    }
}
