package com.bankingax.core.reservation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class VisitReservationPolicyTest {
    @Test
    void reservation_can_be_changed_at_the_24_hour_boundary_but_not_after_it() {
        Instant visit = Instant.parse("2026-11-10T12:00:00Z");

        assertTrue(VisitReservationController.mayChangeBeforeCutoff(Instant.parse("2026-11-09T12:00:00Z"), visit));
        assertTrue(VisitReservationController.mayChangeBeforeCutoff(Instant.parse("2026-11-09T11:59:59Z"), visit));
        assertFalse(VisitReservationController.mayChangeBeforeCutoff(Instant.parse("2026-11-09T12:00:01Z"), visit));
    }
}
