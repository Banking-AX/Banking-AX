package com.bankingax.core.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class ReservationSlotPolicyTest {
    /** Friday 2026-11-06 12:00 in Seoul. */
    private static final Instant NOW = ZonedDateTime.of(2026, 11, 6, 12, 0, 0, 0, ReservationSlotPolicy.ZONE).toInstant();

    private static Instant seoul(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ReservationSlotPolicy.ZONE).toInstant();
    }

    @Test void acceptsAnOfferedWeekdayHour() {
        assertThat(ReservationSlotPolicy.bookable(seoul(11, 9, 10, 0), NOW)).isTrue();
    }

    @Test void judgesTheHourInSeoulTime() {
        assertThat(ReservationSlotPolicy.bookable(Instant.parse("2026-11-09T01:00:00Z"), NOW)).isTrue();
        assertThat(ReservationSlotPolicy.bookable(Instant.parse("2026-11-09T10:00:00Z"), NOW)).isFalse();
    }

    @Test void rejectsWeekendsLunchClosingAndPartialHours() {
        assertThat(ReservationSlotPolicy.bookable(seoul(11, 7, 10, 0), NOW)).isFalse();
        assertThat(ReservationSlotPolicy.bookable(seoul(11, 8, 10, 0), NOW)).isFalse();
        assertThat(ReservationSlotPolicy.bookable(seoul(11, 9, 12, 0), NOW)).isFalse();
        assertThat(ReservationSlotPolicy.bookable(seoul(11, 9, 16, 0), NOW)).isFalse();
        assertThat(ReservationSlotPolicy.bookable(seoul(11, 9, 10, 30), NOW)).isFalse();
    }

    @Test void rejectsThePastTheNext24HoursAndTheFarFuture() {
        assertThat(ReservationSlotPolicy.bookable(NOW.minusSeconds(3600), NOW)).isFalse();
        Instant fridayNoonNow = seoul(11, 5, 12, 0);
        assertThat(ReservationSlotPolicy.bookable(seoul(11, 6, 11, 0), fridayNoonNow)).isFalse();
        assertThat(ReservationSlotPolicy.bookable(seoul(11, 6, 13, 0), fridayNoonNow)).isTrue();
        assertThat(ReservationSlotPolicy.bookable(seoul(11, 16, 10, 0), NOW)).isFalse();
    }

    @Test void listsOnlyOfferedHours() {
        assertThat(ReservationSlotPolicy.slots(LocalDate.of(2026, 11, 9), NOW)).hasSize(5).contains(seoul(11, 9, 10, 0), seoul(11, 9, 15, 0));
        assertThat(ReservationSlotPolicy.slots(LocalDate.of(2026, 11, 7), NOW)).isEmpty();
    }
}
