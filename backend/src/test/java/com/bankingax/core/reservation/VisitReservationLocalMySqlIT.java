package com.bankingax.core.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Booking rules on a disposable schema. Never point T2_VERIFY_DB_URL at the shared banking_ax database: every test truncates. */
@EnabledIfEnvironmentVariable(named = "T2_VERIFY_DB_URL", matches = ".*banking_ax_t2_verify.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class VisitReservationLocalMySqlIT {
    private static final long CUSTOMER = 1, OTHER = 2, STAFF = 101, STAFF_2 = 102, BRANCH = 1, EMPTY_BRANCH = 2, CLOSED_BRANCH = 3;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("T2_VERIFY_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("APP_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("APP_DB_PASSWORD"));
        registry.add("app.case-port", () -> "jpa");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired VisitReservationService reservations;

    @BeforeEach void seed() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks=0");
                for (String table : List.of("event_log", "notification", "visit_reservation", "case_revision", "service_case", "work_item", "workflow", "user", "branch"))
                    statement.execute("truncate table `" + table + "`");
                statement.execute("set foreign_key_checks=1");
            }
            return null;
        });
        jdbc.update("insert into branch(id,branch_code,name,address,is_active) values(1,'B1','가상 지점','가상 주소',true),(2,'B2','직원 없는 지점','가상 주소',true),(3,'B3','닫힌 지점','가상 주소',false)");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values(1,'c1','x','CUSTOMER','c1'),(2,'c2','x','CUSTOMER','c2')");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,branch_id) values(101,'s1','x','STAFF','s1',1)");
        jdbc.update("insert into workflow(id,customer_id,status) values(10,1,'RUNNING'),(20,2,'RUNNING')");
        seedCase(1, CUSTOMER, 10, "REVIEW_PASSED", true);
        seedCase(2, OTHER, 20, "REVIEW_PASSED", true);
        seedCase(3, CUSTOMER, 10, "SUBMITTED", false);
        seedCase(4, CUSTOMER, 10, "REVIEW_REJECTED_DOCUMENT", true);
        seedCase(5, CUSTOMER, 10, "REVIEW_PASSED", false);
    }

    private void seedCase(long id, long customer, long workflow, String status, boolean notified) {
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values(?,?,'INHERITANCE_DEPOSIT',?,'HANDED_OFF',?)", id, workflow, id, "w" + id);
        jdbc.update("insert into service_case(id,work_item_id,customer_id,case_type,case_status,revision,visit_required,application,result_notified_at) "
                + "values(?,?,?,'INHERITANCE_DEPOSIT',?,1,true,'{}',?)", id, id, customer, status, notified ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    /** The first offered hour on the next weekday at least two days away. */
    private static OffsetDateTime slot(int hourOffsetIndex) {
        LocalDate date = LocalDate.now(ReservationSlotPolicy.ZONE).plusDays(2);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) date = date.plusDays(1);
        return ReservationSlotPolicy.slots(date, java.time.Instant.now()).get(hourOffsetIndex).atOffset(ZoneOffset.UTC);
    }

    private VisitReservationController.Create create(String requestId, long caseId, long branch, OffsetDateTime at) {
        return new VisitReservationController.Create(requestId, caseId, branch, null, at);
    }

    private String failure(long customer, VisitReservationController.Create request) {
        return assertThrows(DomainException.class, () -> reservations.create(customer, request)).code();
    }

    private int reservationCount() { return jdbc.queryForObject("select count(*) from visit_reservation", Integer.class); }

    @Test void aNotifiedProceedBooksAStaffMemberAndNotifies() {
        var item = reservations.create(CUSTOMER, create("r1", 1, BRANCH, slot(0)));

        assertThat(item.status()).isEqualTo("CONFIRMED");
        assertThat(item.staffId()).isEqualTo(STAFF);
        assertThat(reservations.list(CUSTOMER)).extracting(VisitReservationController.Item::id).containsExactly(item.id());
        assertThat(jdbc.queryForObject("select content from notification where type='VISIT_CONFIRMED'", String.class)).contains(VisitReservationService.number(item.id()), "가상 지점");
    }

    @Test void onlyANotifiedProceedOfTheOwnerMayBook() {
        for (long caseId : List.of(3L, 4L, 5L, 2L)) assertThat(failure(CUSTOMER, create("r" + caseId, caseId, BRANCH, slot(0)))).isEqualTo("RESERVATION_NOT_ALLOWED");
        assertThat(reservationCount()).isZero();
    }

    @Test void theServerRejectsTimesTheScreenDoesNotOffer() {
        OffsetDateTime weekdayNight = slot(0).plusHours(9);
        assertThat(failure(CUSTOMER, create("r1", 1, BRANCH, OffsetDateTime.now().minusHours(1)))).isEqualTo("RESERVATION_SLOT_UNAVAILABLE");
        assertThat(failure(CUSTOMER, create("r2", 1, BRANCH, weekdayNight))).isEqualTo("RESERVATION_SLOT_UNAVAILABLE");
        assertThat(failure(CUSTOMER, create("r3", 1, BRANCH, null))).isEqualTo("RESERVATION_SLOT_UNAVAILABLE");
        assertThat(reservationCount()).isZero();
    }

    @Test void aClosedBranchOrOneWithoutStaffCannotBeBooked() {
        assertThat(failure(CUSTOMER, create("r1", 1, CLOSED_BRANCH, slot(0)))).isEqualTo("RESERVATION_BRANCH_UNAVAILABLE");
        assertThat(failure(CUSTOMER, create("r2", 1, EMPTY_BRANCH, slot(0)))).isEqualTo("NEEDS_MANUAL_ASSIGNMENT");
        assertThat(reservationCount()).isZero();
    }

    @Test void repeatingARequestReturnsTheSameReservation() {
        var first = reservations.create(CUSTOMER, create("same", 1, BRANCH, slot(0)));
        var second = reservations.create(CUSTOMER, create("same", 1, BRANCH, slot(0)));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(reservationCount()).isOne();
        assertThat(jdbc.queryForObject("select count(*) from notification where type='VISIT_CONFIRMED'", Integer.class)).isOne();
    }

    @Test void anotherCustomerCannotReplayARequest() {
        reservations.create(CUSTOMER, create("same", 1, BRANCH, slot(0)));

        assertThat(failure(OTHER, create("same", 1, BRANCH, slot(0)))).isEqualTo("RESERVATION_NOT_ALLOWED");
    }

    @Test void aCaseHoldsOneActiveReservation() {
        reservations.create(CUSTOMER, create("r1", 1, BRANCH, slot(0)));

        assertThat(failure(CUSTOMER, create("r2", 1, BRANCH, slot(1)))).isEqualTo("RESERVATION_ALREADY_EXISTS");
    }

    @Test void aTakenTimeBecomesUnavailableAndCannotBeBooked() {
        reservations.create(CUSTOMER, create("r1", 1, BRANCH, slot(0)));

        var day = slot(0).atZoneSameInstant(ReservationSlotPolicy.ZONE).toLocalDate();
        assertThat(reservations.availability(BRANCH, day).get(0).available()).isFalse();
        assertThat(reservations.availability(BRANCH, day).get(1).available()).isTrue();
        assertThat(failure(OTHER, create("r2", 2, BRANCH, slot(0)))).isEqualTo("NEEDS_MANUAL_ASSIGNMENT");
    }

    @Test void twoCustomersRacingForOneStaffTimeLeaveOneReservation() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        List<Future<String>> calls = new ArrayList<>();
        for (long[] actor : List.of(new long[] {CUSTOMER, 1}, new long[] {OTHER, 2}))
            calls.add(pool.submit(() -> {
                start.await();
                try { reservations.create(actor[0], create("race-" + actor[0], actor[1], BRANCH, slot(0))); return "OK"; }
                catch (DomainException exception) { return exception.code(); }
            }));
        start.countDown();
        List<String> results = new ArrayList<>();
        for (var call : calls) results.add(call.get());
        pool.shutdown();

        assertThat(results).contains("OK").filteredOn(result -> !result.equals("OK")).hasSize(1).allMatch(code -> code.equals("RESERVATION_CONFLICT") || code.equals("NEEDS_MANUAL_ASSIGNMENT"));
        assertThat(reservationCount()).isOne();
        assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class)).isOne();
    }

    /** Capacity is the number of free staff: every one of them is used, and none is booked twice. */
    @Test void threeCustomersRacingForTwoStaffFillBothAndNoOneTwice() throws Exception {
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,branch_id) values(102,'s2','x','STAFF','s2',1)");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values(3,'c3','x','CUSTOMER','c3')");
        jdbc.update("insert into workflow(id,customer_id,status) values(30,3,'RUNNING')");
        seedCase(6, 3, 30, "REVIEW_PASSED", true);
        var pool = Executors.newFixedThreadPool(3);
        var start = new CountDownLatch(1);
        List<Future<String>> calls = new ArrayList<>();
        for (long[] actor : List.of(new long[] {CUSTOMER, 1}, new long[] {OTHER, 2}, new long[] {3, 6}))
            calls.add(pool.submit(() -> {
                start.await();
                try { reservations.create(actor[0], create("race3-" + actor[0], actor[1], BRANCH, slot(0))); return "OK"; }
                catch (DomainException exception) { return exception.code(); }
            }));
        start.countDown();
        List<String> results = new ArrayList<>();
        for (var call : calls) results.add(call.get());
        pool.shutdown();

        long booked = results.stream().filter("OK"::equals).count();
        assertThat(booked).as("both free staff are used").isEqualTo(2);
        assertThat(reservationCount()).isEqualTo((int) booked);
        assertThat(jdbc.queryForObject("select count(distinct assigned_staff_id) from visit_reservation", Integer.class)).isEqualTo((int) booked);
    }

    @Test void aFailedBookingLeavesNoAuditOrNotification() {
        reservations.create(CUSTOMER, create("r1", 1, BRANCH, slot(0)));
        int events = jdbc.queryForObject("select count(*) from event_log", Integer.class);

        failure(OTHER, create("r2", 2, BRANCH, slot(0)));

        assertThat(jdbc.queryForObject("select count(*) from event_log", Integer.class)).isEqualTo(events);
        assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class)).isOne();
    }

    @Test void cancelingFreesTheTimeAndRecordsIt() {
        var item = reservations.create(CUSTOMER, create("r1", 1, BRANCH, slot(0)));

        reservations.cancel(CUSTOMER, item.id(), "c1");

        assertThat(jdbc.queryForObject("select count(*) from event_log where event_type='VISIT_CANCELED'", Integer.class)).isOne();
        assertThat(reservations.create(OTHER, create("r2", 2, BRANCH, slot(0))).status()).isEqualTo("CONFIRMED");
    }

    @Test void anotherCustomerCannotCancel() {
        var item = reservations.create(CUSTOMER, create("r1", 1, BRANCH, slot(0)));

        assertThat(assertThrows(DomainException.class, () -> reservations.cancel(OTHER, item.id(), "c1")).code()).isEqualTo("NOT_FOUND");
    }
}
