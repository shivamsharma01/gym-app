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
     * The state to show staff. A stored Online from the last report is Offline once the gym PC
     * is no longer online. A reader with no gym PC keeps the state it has.
     */
    public static DeviceConnectionState shown(Device device, GatewayStatus gatewayStatus) {
        if (device.getGatewayId() != null && gatewayStatus != GatewayStatus.ONLINE) {
            return DeviceConnectionState.OFFLINE;
        }
        DeviceConnectionState reported = device.getConnectionState();
        return reported == null ? DeviceConnectionState.UNKNOWN : reported;
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
