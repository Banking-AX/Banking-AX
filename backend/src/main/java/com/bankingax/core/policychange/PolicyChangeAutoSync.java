package com.bankingax.core.policychange;

import com.bankingax.core.persistence.ConditionalOnDataSource;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pulls bank change events on a fixed delay, so changes arrive without a staff member pressing the button. It only
 * receives: analysis and every decision stay with staff. Off unless APP_POLICY_AUTO_SYNC_ENABLED=true.
 */
@Component
@ConditionalOnDataSource
@ConditionalOnProperty(name = "app.policy-change.auto-sync-enabled", havingValue = "true")
class PolicyChangeAutoSync {
    private static final Logger log = LoggerFactory.getLogger(PolicyChangeAutoSync.class);
    private final PolicyChangeIngestService ingest;

    PolicyChangeAutoSync(PolicyChangeIngestService ingest) { this.ingest = ingest; }

    @Scheduled(initialDelayString = "${app.policy-change.auto-sync-initial-ms:30000}", fixedDelayString = "${app.policy-change.auto-sync-ms:60000}")
    void run() {
        try {
            ingest.syncAutomatically("auto-" + UUID.randomUUID());
        } catch (RuntimeException failure) {
            log.warn("Automatic bank change sync failed: {}", failure.getClass().getSimpleName());
        }
    }
}
