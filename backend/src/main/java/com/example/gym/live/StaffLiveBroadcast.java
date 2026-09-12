package com.example.gym.live;

import java.util.Map;

public record StaffLiveBroadcast(Long tenantId, String type, Map<String, Object> payload) {
}
