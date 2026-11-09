package com.bankingax.core.reservation;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Demo visiting hours, in bank time. The server decides these so a crafted request cannot book outside them;
 * branch_slot is not in the Flyway schema, so capacity comes from the branch's free staff instead of slot rows.
 */
final class ReservationSlotPolicy {
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final List<Integer> HOURS = List.of(10, 11, 13, 14, 15);
    /** A visit inside the change window could not be changed or canceled any more, so it is not offered. */
    private static final Duration LEAD = Duration.ofHours(24);
    private static final int HORIZON_DAYS = 7;

    private ReservationSlotPolicy() { }

    static boolean bookable(Instant at, Instant now) {
        ZonedDateTime local = at.atZone(ZONE);
        return isOpen(local.toLocalDate(), now) && HOURS.contains(local.getHour()) && local.getMinute() == 0 && local.getSecond() == 0 && local.getNano() == 0
                && at.isAfter(now.plus(LEAD));
    }

    static List<Instant> slots(LocalDate date, Instant now) {
        return isOpen(date, now) ? HOURS.stream().map(hour -> date.atTime(hour, 0).atZone(ZONE).toInstant()).filter(at -> at.isAfter(now.plus(LEAD))).toList() : List.of();
    }

    private static boolean isOpen(LocalDate date, Instant now) {
        LocalDate today = now.atZone(ZONE).toLocalDate();
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY && date.isAfter(today) && !date.isAfter(today.plusDays(HORIZON_DAYS));
    }
}
