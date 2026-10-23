package com.example.gym.audit;

import java.util.List;

public record AuditFilterOptions(List<String> actors, List<String> actions) {
}
