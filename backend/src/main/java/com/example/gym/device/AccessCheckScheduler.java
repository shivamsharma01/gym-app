package com.example.gym.device;

import com.example.gym.device.repo.MemberDeviceMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves members' devices along as memberships start and end: when the membership on the devices
 * has ended, the next one's dates are sent (enabled if paid), or the member is disabled when there
 * is none; a membership that must wait for its start day is enabled on that day. Runs hourly
 * (only members whose window actually changed get commands) and once at startup to catch up after
 * downtime. "Today" is the server's local date, so the server must run in the gym's time zone.
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
        private final TransactionTemplate transactions;

        public AccessCheck(MemberDeviceMappingRepository mappingRepository,
                           DeviceAuthorizationService authorizationService,
                           TransactionTemplate transactions) {
            this.mappingRepository = mappingRepository;
            this.authorizationService = authorizationService;
            this.transactions = transactions;
        }

        /** Returns how many members got new access commands. */
        public int run() {
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
