package com.example.gym.notification;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberService;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import com.example.gym.notification.dto.CreateAnnouncement;
import com.example.gym.notification.dto.SendNotification;
import com.example.gym.notification.dto.UpsertTemplate;
import com.example.gym.settings.GymProfileRepository;
import com.example.gym.tenant.TenantGuard;
import com.example.gym.tenant.TenantRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationTemplateRepository templateRepository;
    private final OutboundNotificationRepository outboundRepository;
    private final AnnouncementRepository announcementRepository;
    private final MemberService memberService;
    private final MemberRepository memberRepository;
    private final MembershipRepository membershipRepository;
    private final MockNotificationAdapter mockAdapter;
    private final AuditService auditService;
    private final TenantRepository tenantRepository;
    private final GymProfileRepository profileRepository;

    public NotificationService(NotificationTemplateRepository templateRepository,
                               OutboundNotificationRepository outboundRepository,
                               AnnouncementRepository announcementRepository,
                               MemberService memberService,
                               MemberRepository memberRepository,
                               MembershipRepository membershipRepository,
                               MockNotificationAdapter mockAdapter,
                               AuditService auditService,
                               TenantRepository tenantRepository,
                               GymProfileRepository profileRepository) {
        this.templateRepository = templateRepository;
        this.outboundRepository = outboundRepository;
        this.announcementRepository = announcementRepository;
        this.memberService = memberService;
        this.memberRepository = memberRepository;
        this.membershipRepository = membershipRepository;
        this.mockAdapter = mockAdapter;
        this.auditService = auditService;
        this.tenantRepository = tenantRepository;
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public List<NotificationTemplate> templates(Long tenantId) {
        requireTenant(tenantId);
        return templateRepository.findByTenantIdOrderByTemplateKeyAsc(tenantId);
    }

    @Transactional
    public NotificationTemplate upsertTemplate(UpsertTemplate request, Long tenantId) {
        requireTenant(tenantId);
        NotificationTemplate template = templateRepository
                .findByTenantIdAndTemplateKeyAndChannel(tenantId, request.templateKey(), request.channel())
                .orElseGet(() -> new NotificationTemplate(
                        tenantId, request.templateKey(), request.channel(), request.subject(), request.body()));
        template.setSubject(request.subject());
        template.setBody(request.body());
        return templateRepository.save(template);
    }

    @Transactional
    public OutboundNotification send(SendNotification request, Long tenantId) {
        requireTenant(tenantId);
        Member member = memberService.getByPublicId(request.memberId(), tenantId);
        NotificationTemplate template = templateRepository
                .findByTenantIdAndTemplateKeyAndChannel(tenantId, request.templateKey(), request.channel())
                .orElseThrow(() -> CommonExceptions.notFound("Template"));
        return queueAndDeliver(tenantId, member, null, template.getTemplateKey(), request.channel(),
                template.getSubject(), template.getBody());
    }

    @Transactional
    public int queueExpiryReminders(Long tenantId) {
        requireTenant(tenantId);
        LocalDate today = LocalDate.now();
        LocalDate until = today.plusDays(7);
        int queued = 0;
        for (Membership membership : membershipRepository.findByTenantId(tenantId)) {
            if (membership.getStatus() != MembershipStatus.ACTIVE
                    || membership.getEndDate().isBefore(today)
                    || membership.getEndDate().isAfter(until)) {
                continue;
            }
            Member member = memberRepository.findById(membership.getMemberId()).orElse(null);
            if (member == null) {
                continue;
            }
            TenantGuard.check(member, tenantId, "Member");
            NotificationChannel channel = member.getEmail() != null
                    ? NotificationChannel.EMAIL : NotificationChannel.IN_APP;
            NotificationTemplate template = templateRepository
                    .findByTenantIdAndTemplateKeyAndChannel(tenantId, "EXPIRY_REMINDER", channel)
                    .orElse(null);
            String body = template == null
                    ? "Hi {{memberName}}, your membership expires on {{expiryDate}} ({{daysRemaining}} days)."
                    : template.getBody();
            String subject = template == null ? "Membership expiry" : template.getSubject();
            queueAndDeliver(tenantId, member, membership, "EXPIRY_REMINDER", channel, subject, body);
            queued++;
        }
        return queued;
    }

    @Transactional(readOnly = true)
    public Page<OutboundNotification> outbound(Long tenantId, Pageable pageable) {
        requireTenant(tenantId);
        return outboundRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
    }

    @Transactional
    public Announcement createAnnouncement(CreateAnnouncement request, Long tenantId) {
        requireTenant(tenantId);
        Announcement announcement = announcementRepository.save(
                new Announcement(tenantId, request.title(), request.body(), request.published()));
        auditService.record(AuditActions.ANNOUNCEMENT_CREATED, AuditActions.RESULT_SUCCESS,
                "Announcement", announcement.getPublicId(), null);
        return announcement;
    }

    @Transactional(readOnly = true)
    public List<Announcement> announcements(Long tenantId) {
        requireTenant(tenantId);
        return announcementRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    private OutboundNotification queueAndDeliver(Long tenantId, Member member, Membership membership,
                                                 String templateKey, NotificationChannel channel,
                                                 String subjectTemplate, String bodyTemplate) {
        String recipient = recipientFor(member, channel);
        String body = render(bodyTemplate, member, membership, tenantId);
        String subject = subjectTemplate == null ? null : render(subjectTemplate, member, membership, tenantId);
        OutboundNotification outbound = outboundRepository.save(new OutboundNotification(
                tenantId, channel, templateKey, recipient, subject, body, member.getId()));
        try {
            mockAdapter.send(outbound);
            outbound.markSent();
        } catch (RuntimeException ex) {
            outbound.markFailed(ex.getMessage());
        }
        outboundRepository.save(outbound);
        auditService.record(AuditActions.NOTIFICATION_QUEUED, AuditActions.RESULT_SUCCESS,
                "OutboundNotification", outbound.getPublicId(),
                Map.of("channel", channel.name(), "member", member.getPublicId()));
        return outbound;
    }

    private String render(String template, Member member, Membership membership, Long tenantId) {
        if (template == null) {
            return null;
        }
        String days = membership == null ? ""
                : String.valueOf(Math.max(0, membership.getEndDate().toEpochDay() - LocalDate.now().toEpochDay()));
        return template
                .replace("{{memberName}}", member.getFullName())
                .replace("{{gymName}}", gymDisplayName(tenantId))
                .replace("{{expiryDate}}", membership == null ? "" : membership.getEndDate().toString())
                .replace("{{daysRemaining}}", days);
    }

    private String gymDisplayName(Long tenantId) {
        if (tenantId == null) {
            return "Gym";
        }
        return profileRepository.findByTenantId(tenantId)
                .map(p -> p.getDisplayName())
                .filter(n -> n != null && !n.isBlank())
                .or(() -> tenantRepository.findById(tenantId).map(t -> t.getName()))
                .orElse("Gym");
    }

    private String recipientFor(Member member, NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> {
                if (member.getEmail() == null || member.getEmail().isBlank()) {
                    throw CommonExceptions.badRequest("Member has no email");
                }
                yield member.getEmail();
            }
            case SMS -> {
                if (member.getPhone() == null || member.getPhone().isBlank()) {
                    throw CommonExceptions.badRequest("Member has no phone");
                }
                yield member.getPhone();
            }
            case IN_APP -> member.getPublicId();
        };
    }

    private void requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw CommonExceptions.badRequest("A gym tenant is required");
        }
    }
}
