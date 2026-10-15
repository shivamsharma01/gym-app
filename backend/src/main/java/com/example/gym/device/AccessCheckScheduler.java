package com.example.gym.device;

import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.membership.MembershipExpiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes a new desired revision when a mapped member's access window changes as memberships
 * start and end. Payment is not consulted. Runs hourly, and once at startup. "Today" is the
 * server's local date, so the server must run in the gym's time zone.
 */
@Component
@ConditionalOnProperty(prefix = "app.gateway.outbox", name = "dispatcher-enabled",
        havingValue = "true", matchIfMissing = true)
public class AccessCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccessCheckScheduler.class);

    private final AccessCheck accessCheck;

    public AccessCheckScheduler(AccessCheck accessCheck) {
        this.accessCheck = accessCheck;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        run();
    }

    @Scheduled(cron = "${app.gateway.access-check-cron:0 1 * * * *}")
    public void run() {
        try {
            accessCheck.run();
        } catch (RuntimeException ex) {
            log.error("Access check failed", ex);
        }
    }

    /** The check itself, separate so tests can run it without the schedule. */
    @Component
    public static class AccessCheck {

        private final MemberDeviceMappingRepository mappingRepository;
        private final DeviceAuthorizationService authorizationService;
        private final MembershipExpiry membershipExpiry;
        private final TransactionTemplate transactions;

        public AccessCheck(MemberDeviceMappingRepository mappingRepository,
                           DeviceAuthorizationService authorizationService,
                           MembershipExpiry membershipExpiry,
                           TransactionTemplate transactions) {
            this.mappingRepository = mappingRepository;
            this.authorizationService = authorizationService;
            this.membershipExpiry = membershipExpiry;
            this.transactions = transactions;
        }

        /** Returns how many members had a changed window published as a desired revision. */
        public int run() {
            membershipExpiry.expireElapsed();
            int updated = 0;
            for (Long memberId : mappingRepository.findDistinctMemberIds()) {
                try {
                    if (Boolean.TRUE.equals(transactions.execute(s -> authorizationService.refresh(memberId)))) {
                        updated++;
                    }
                } catch (RuntimeException ex) {
                    log.warn("Access check failed for member {}", memberId, ex);
                }
            }
            if (updated > 0) {
                log.info("Access check: updated devices for {} member(s)", updated);
            }
            return updated;
        }
    }
}
