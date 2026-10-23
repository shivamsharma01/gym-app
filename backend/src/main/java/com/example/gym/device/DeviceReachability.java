package com.example.gym.device;

import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceConnectionState;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.GatewayStatus;
import com.example.gym.device.repo.GatewayRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** A reader is reachable only while the gym PC that talks to it is online. */
@Component
public class DeviceReachability {

    private final GatewayRepository gateways;

    public DeviceReachability(GatewayRepository gateways) {
        this.gateways = gateways;
    }

    /**
     * The state to show staff. A gym PC that has gone offline takes its readers offline with it,
     * including a reader still marked online from the last report. A PC that has never checked
     * in leaves a reader that has never reported as unknown.
     */
    public static DeviceConnectionState shown(Device device, GatewayStatus gatewayStatus) {
        DeviceConnectionState reported = device.getConnectionState() == null
                ? DeviceConnectionState.UNKNOWN : device.getConnectionState();
        if (device.getGatewayId() == null || gatewayStatus == GatewayStatus.ONLINE) {
            return reported;
        }
        if (gatewayStatus == GatewayStatus.OFFLINE || reported == DeviceConnectionState.ONLINE) {
            return DeviceConnectionState.OFFLINE;
        }
        return reported;
    }

    public DeviceConnectionState shown(Device device) {
        return shown(device, status(device.getGatewayId()));
    }

    public Map<Long, DeviceConnectionState> shown(Collection<Device> devices) {
        Set<Long> ids = devices.stream().map(Device::getGatewayId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, GatewayStatus> statuses = ids.isEmpty()
                ? Map.of()
                : gateways.findAllById(ids).stream()
                        .collect(Collectors.toMap(Gateway::getId, Gateway::getStatus));
        Map<Long, DeviceConnectionState> out = new HashMap<>();
        for (Device device : devices) {
            GatewayStatus status = device.getGatewayId() == null ? null : statuses.get(device.getGatewayId());
            out.put(device.getId(), shown(device, status));
        }
        return out;
    }

    private GatewayStatus status(Long gatewayId) {
        if (gatewayId == null) {
            return null;
        }
        return gateways.findById(gatewayId).map(Gateway::getStatus).orElse(null);
    }
}
