package com.bankingax.core.reservation;

import com.bankingax.core.persistence.ConditionalOnDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** NOTI-23 runs on a timer; switch off with app.jobs.enabled=false. Each run is idempotent. */
@Configuration
@EnableScheduling
@ConditionalOnDataSource
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationJobs {
    private static final Logger log = LoggerFactory.getLogger(ReservationJobs.class);
    private final VisitReservationService reservations;

    public ReservationJobs(VisitReservationService reservations) { this.reservations = reservations; }

    @Scheduled(initialDelayString = "${app.jobs.initial-delay-ms:300000}", fixedDelayString = "${app.jobs.reservation-expiry-ms:300000}")
    public void expireUnassignedReservations() {
        int expired = reservations.expireUnassigned();
        if (expired > 0) log.info("Expired {} reservation request(s) that reached their visit time without staff.", expired);
    }
}
