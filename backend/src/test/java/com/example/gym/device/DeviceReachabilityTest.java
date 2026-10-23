package com.example.gym.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceConnectionState;
import com.example.gym.device.domain.DeviceRole;
import com.example.gym.device.domain.GatewayStatus;
import org.junit.jupiter.api.Test;

class DeviceReachabilityTest {

    @Test
    void aReaderIsOfflineWhileItsGymPcIsOffline() {
        Device reader = new Device(1L, "Entry", DeviceRole.ENTRANCE);
        reader.setGatewayId(9L);
        reader.setConnectionState(DeviceConnectionState.ONLINE);

        assertThat(DeviceReachability.shown(reader, GatewayStatus.OFFLINE)).isEqualTo(DeviceConnectionState.OFFLINE);
        assertThat(DeviceReachability.shown(reader, GatewayStatus.UNKNOWN)).isEqualTo(DeviceConnectionState.OFFLINE);
        assertThat(DeviceReachability.shown(reader, null)).isEqualTo(DeviceConnectionState.OFFLINE);

        reader.setConnectionState(DeviceConnectionState.UNKNOWN);
        assertThat(DeviceReachability.shown(reader, GatewayStatus.UNKNOWN)).isEqualTo(DeviceConnectionState.UNKNOWN);
        assertThat(DeviceReachability.shown(reader, GatewayStatus.OFFLINE)).isEqualTo(DeviceConnectionState.OFFLINE);
    }

    @Test
    void aReaderStaysOnlineOnlyWhileItsGymPcIsOnline() {
        Device reader = new Device(1L, "Entry", DeviceRole.ENTRANCE);
        reader.setGatewayId(9L);
        reader.setConnectionState(DeviceConnectionState.ONLINE);

        assertThat(DeviceReachability.shown(reader, GatewayStatus.ONLINE)).isEqualTo(DeviceConnectionState.ONLINE);

        reader.setConnectionState(DeviceConnectionState.OFFLINE);
        assertThat(DeviceReachability.shown(reader, GatewayStatus.ONLINE)).isEqualTo(DeviceConnectionState.OFFLINE);
    }

    @Test
    void aReaderWithNoGymPcKeepsItsOwnState() {
        Device reader = new Device(1L, "Loose", DeviceRole.ENTRANCE);
        reader.setConnectionState(DeviceConnectionState.UNKNOWN);

        assertThat(DeviceReachability.shown(reader, null)).isEqualTo(DeviceConnectionState.UNKNOWN);
    }
}
