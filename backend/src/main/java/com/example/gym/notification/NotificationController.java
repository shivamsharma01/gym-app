package com.example.gym.notification;

import com.example.gym.common.web.PageResponse;
import com.example.gym.notification.dto.AnnouncementView;
import com.example.gym.notification.dto.CreateAnnouncement;
import com.example.gym.notification.dto.OutboundView;
import com.example.gym.notification.dto.SendNotification;
import com.example.gym.notification.dto.TemplateView;
import com.example.gym.notification.dto.UpsertTemplate;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Notifications")
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/notification-templates")
    @PreAuthorize("hasAuthority('NOTIFICATION_SEND')")
    public List<TemplateView> templates() {
        return notificationService.templates(SecurityUtils.currentTenantId()).stream()
                .map(TemplateView::from)
                .toList();
    }

    @PutMapping("/notification-templates")
    @PreAuthorize("hasAuthority('NOTIFICATION_SEND')")
    public TemplateView upsertTemplate(@Valid @RequestBody UpsertTemplate request) {
        return TemplateView.from(notificationService.upsertTemplate(request, SecurityUtils.currentTenantId()));
    }

    @GetMapping("/notifications")
    @PreAuthorize("hasAuthority('NOTIFICATION_SEND')")
    public PageResponse<OutboundView> outbound(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.from(
                notificationService.outbound(SecurityUtils.currentTenantId(),
                        PageRequest.of(Math.max(page, 0), safeSize)),
                OutboundView::from);
    }

    @PostMapping("/notifications")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('NOTIFICATION_SEND')")
    @Operation(summary = "Queue a notification to a member (mock delivery in this deployment)")
    public OutboundView send(@Valid @RequestBody SendNotification request) {
        return OutboundView.from(notificationService.send(request, SecurityUtils.currentTenantId()));
    }

    @PostMapping("/notifications/expiry-reminders")
    @PreAuthorize("hasAuthority('NOTIFICATION_SEND')")
    @Operation(summary = "Queue mock expiry reminders for memberships ending within 7 days")
    public Map<String, Integer> expiryReminders() {
        int queued = notificationService.queueExpiryReminders(SecurityUtils.currentTenantId());
        return Map.of("queued", queued);
    }

    @GetMapping("/announcements")
    @PreAuthorize("hasAuthority('NOTIFICATION_SEND')")
    public List<AnnouncementView> announcements() {
        return notificationService.announcements(SecurityUtils.currentTenantId()).stream()
                .map(AnnouncementView::from)
                .toList();
    }

    @PostMapping("/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('NOTIFICATION_SEND')")
    public AnnouncementView createAnnouncement(@Valid @RequestBody CreateAnnouncement request) {
        return AnnouncementView.from(
                notificationService.createAnnouncement(request, SecurityUtils.currentTenantId()));
    }
}
