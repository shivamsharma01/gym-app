package com.example.gym.audit;

import com.example.gym.common.web.CorrelationIdFilter;
import com.example.gym.security.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes immutable audit records. Runs in its own transaction so audit entries persist even when
 * the surrounding business transaction rolls back (e.g. failed logins). Never records secrets.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final JsonMapper jsonMapper;

    public AuditService(AuditLogRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /** Records an action for the currently authenticated caller (if any). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String result, String resourceType, String resourceId,
                       Map<String, Object> details) {
        AuditLog entry = base(action, result, resourceType, resourceId, details);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal principal) {
            entry.actorUserId(principal.getUserId())
                    .actorUsername(principal.getUsername())
                    .tenantId(principal.getTenantId());
        }
        persist(entry);
    }

    /** Records an auth-flow action where the security context may not yet be established. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuth(String action, String result, Long actorUserId, String actorUsername,
                           Long tenantId, Map<String, Object> details) {
        AuditLog entry = base(action, result, "AUTH", actorUsername, details)
                .actorUserId(actorUserId)
                .actorUsername(actorUsername)
                .tenantId(tenantId);
        persist(entry);
    }

    private AuditLog base(String action, String result, String resourceType, String resourceId,
                          Map<String, Object> details) {
        AuditLog entry = new AuditLog()
                .action(action)
                .result(result)
                .resourceType(resourceType)
                .resourceId(resourceId);
        if (details != null && !details.isEmpty()) {
            entry.details(jsonMapper.writeValueAsString(details));
        }
        HttpServletRequest request = currentRequest();
        if (request != null) {
            entry.ipAddress(clientIp(request))
                    .userAgent(truncate(request.getHeader("User-Agent"), 256))
                    .correlationId(stringAttr(request, CorrelationIdFilter.ATTRIBUTE));
        }
        return entry;
    }

    private void persist(AuditLog entry) {
        try {
            repository.save(entry);
        } catch (RuntimeException ex) {
            // Auditing must never break the primary flow; log and continue.
            log.error("Failed to persist audit log for action {}", entry.getAction(), ex);
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return truncate(forwarded.split(",")[0].trim(), 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private String stringAttr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return v == null ? null : v.toString();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
