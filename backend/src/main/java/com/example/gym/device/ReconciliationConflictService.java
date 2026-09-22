package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.ReconciliationConflict;
import com.example.gym.device.domain.ReconciliationConflictType;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.device.repo.ReconciliationConflictRepository;
import com.example.gym.tenant.TenantGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationConflictService {

    private final ReconciliationConflictRepository conflictRepository;
    private final DeviceService deviceService;
    private final DeviceSyncService deviceSyncService;
    private final AuditService auditService;

    public ReconciliationConflictService(ReconciliationConflictRepository conflictRepository,
                                         DeviceService deviceService,
                                         DeviceSyncService deviceSyncService,
                                         AuditService auditService) {
        this.conflictRepository = conflictRepository;
        this.deviceService = deviceService;
        this.deviceSyncService = deviceSyncService;
        this.auditService = auditService;
    }

    /**
     * @param action REMOVE — enqueue REMOVE_USER for EXTRA_DEVICE_USER; DISMISS — close without device change
     */
    @Transactional
    public ReconciliationConflict resolve(String devicePublicId, String conflictPublicId,
                                          String action, Long tenantId) {
        Device device = deviceService.getByPublicId(devicePublicId, tenantId);
        ReconciliationConflict conflict = conflictRepository.findByPublicId(conflictPublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Reconciliation conflict"));
        TenantGuard.check(conflict.getTenantId(), tenantId, "Reconciliation conflict");
        if (!conflict.getDeviceId().equals(device.getId())) {
            throw CommonExceptions.notFound("Reconciliation conflict");
        }
        String act = action == null ? "DISMISS" : action.toUpperCase();
        if ("REMOVE".equals(act)) {
            if (conflict.getConflictType() != ReconciliationConflictType.EXTRA_DEVICE_USER
                    && conflict.getConflictType() != ReconciliationConflictType.AUTH_MISMATCH) {
                throw CommonExceptions.badRequest(
                        "REMOVE is only valid for EXTRA_DEVICE_USER or AUTH_MISMATCH conflicts");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("deviceUserId", conflict.getDeviceUserId());
            deviceSyncService.enqueue(tenantId, device.getId(), null, null,
                    SyncCommandType.REMOVE_USER, payload);
            conflict.resolve();
        } else if ("DISMISS".equals(act)) {
            conflict.dismiss();
        } else {
            throw CommonExceptions.badRequest("action must be REMOVE or DISMISS");
        }
        ReconciliationConflict saved = conflictRepository.save(conflict);
        auditService.record(AuditActions.RECONCILIATION_CONFLICT_RESOLVED, AuditActions.RESULT_SUCCESS,
                "ReconciliationConflict", saved.getPublicId(), Map.of("action", act));
        return saved;
    }
}
