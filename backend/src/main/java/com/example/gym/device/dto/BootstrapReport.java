package com.example.gym.device.dto;

import java.util.List;

/**
 * One bootstrap run. Rows classify the roster. They do not link anyone.
 */
public record BootstrapReport(String runId, List<BootstrapReport.Row> rows) {

    public record Row(
            String outcome,
            String deviceId,
            String deviceUserId,
            String memberId,
            String readerName,
            String serverName,
            String suggestionMemberId) {
    }
}
