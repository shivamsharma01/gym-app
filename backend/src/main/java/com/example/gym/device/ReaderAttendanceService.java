package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.AttendanceIngestionService.Punch;
import com.example.gym.device.AttendanceIngestionService.WindowResult;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.dto.AttendanceWindowRequests.AttendanceCursor;
import com.example.gym.device.dto.AttendanceWindowRequests.AttendanceWindow;
import com.example.gym.device.dto.AttendanceWindowRequests.PunchRecord;
import com.example.gym.device.repo.DeviceRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** The gateway's attendance poll: where a reader's next window starts, and storing one window. */
@Service
public class ReaderAttendanceService {

    private final DeviceRepository devices;
    private final AttendanceIngestionService ingestion;

    public ReaderAttendanceService(DeviceRepository devices, AttendanceIngestionService ingestion) {
        this.devices = devices;
        this.ingestion = ingestion;
    }

    public AttendanceCursor cursor(Gateway gateway, String devicePublicId) {
        Device device = ownedReader(gateway, devicePublicId);
        return new AttendanceCursor(device.getPublicId(), ingestion.lastEventAt(device), 0);
    }

    public AttendanceCursor store(Gateway gateway, AttendanceWindow window) {
        Device device = ownedReader(gateway, window.deviceId());
        List<Punch> punches = window.punches().stream().map(ReaderAttendanceService::punch).toList();
        WindowResult result = ingestion.storeWindow(device, punches);
        FlowLog.info("attendance", "window device={} read={} stored={} lastEventAt={}",
                device.getPublicId(), punches.size(), result.stored(), result.lastEventAt());
        return new AttendanceCursor(device.getPublicId(), result.lastEventAt(), result.stored());
    }

    private static Punch punch(PunchRecord record) {
        String user = StringUtils.hasText(record.deviceUserId()) ? record.deviceUserId().trim() : null;
        String method = StringUtils.hasText(record.method()) ? record.method().trim() : "UNKNOWN";
        return new Punch(user, record.occurredAt(), method, record.granted(), record.recNo(),
                AttendanceIngestionService.denyReason(record.errorCode()));
    }

    private Device ownedReader(Gateway gateway, String devicePublicId) {
        Device device = devices.findByPublicId(devicePublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Reader"));
        if (!device.getTenantId().equals(gateway.getTenantId())) {
            throw CommonExceptions.notFound("Reader");
        }
        if (device.getGatewayId() == null || !device.getGatewayId().equals(gateway.getId())) {
            throw CommonExceptions.forbidden("Reader is not on this gateway");
        }
        return device;
    }
}
