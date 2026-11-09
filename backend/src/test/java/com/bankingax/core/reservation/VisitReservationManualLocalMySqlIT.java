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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** VISIT-08/10 and NOTI-23 with a ranking that fails. Never point T2_VERIFY_DB_URL at the shared banking_ax database: every test truncates. */
@EnabledIfEnvironmentVariable(named = "T2_VERIFY_DB_URL", matches = ".*banking_ax_t2_verify.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = {com.bankingax.BankingAxApplication.class, VisitReservationManualLocalMySqlIT.Ranking.class})
class VisitReservationManualLocalMySqlIT {
    private static final long CUSTOMER = 1, OTHER = 2, STAFF = 101, STAFF_2 = 102, BRANCH = 1, EMPTY_BRANCH = 2, CLOSED_BRANCH = 3;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("T2_VERIFY_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("APP_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("APP_DB_PASSWORD"));
        registry.add("app.case-port", () -> "jpa");
        registry.add("app.jobs.enabled", () -> "false");
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
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,branch_id) values(101,'s1','x','STAFF','s1',1),(102,'s2','x','STAFF','s2',1),(201,'other','x','STAFF','other',2)");
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

    static volatile boolean rankingFails = true;
    @TestConfiguration static class Ranking {
        @Bean @Primary StaffRankingPort failing() {
            return (candidates, task) -> { if (rankingFails) throw new IllegalStateException("ranking down"); return candidates.stream().map(StaffRankingPort.Candidate::staffId).toList(); };
        }
    }

    private VisitReservationController.Create create(String requestId, long caseId, long branch, OffsetDateTime at) {
        return new VisitReservationController.Create(requestId, caseId, branch, null, at);
    }
    private String code(Runnable call) { return assertThrows(DomainException.class, call::run).code(); }
    private String status(long id) { return jdbc.queryForObject("select status from visit_reservation where id=?", String.class, id); }

    @Test void aFailedRankingWaitsForAManualAssignmentWithoutHoldingStaff() {
        rankingFails = true;
        var item = reservations.create(CUSTOMER, create("m1", 1, BRANCH, slot(0)));

        assertThat(item.status()).isEqualTo("NEEDS_MANUAL_ASSIGNMENT");
        assertThat(jdbc.queryForObject("select assigned_staff_id from visit_reservation where id=?", Long.class, item.id())).isNull();
        assertThat(jdbc.queryForObject("select count(*) from notification where type='VISIT_PENDING_ASSIGNMENT'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification where type='VISIT_CONFIRMED'", Integer.class)).isZero();
        assertThat(reservations.manualCandidates(STAFF, item.id())).extracting(StaffRankingPort.Candidate::staffId).containsExactlyInAnyOrder(101L, 102L);
    }

    @Test void onlyAFreeStaffMemberOfTheSameBranchCanBeAssigned() {
        rankingFails = true;
        var item = reservations.create(CUSTOMER, create("m2", 1, BRANCH, slot(0)));
        assertThat(code(() -> reservations.manualAssign(201, item.id(), 101, "a0"))).isEqualTo("FORBIDDEN");
        assertThat(code(() -> reservations.manualAssign(STAFF, item.id(), 201, "a1"))).isEqualTo("RESERVATION_CONFLICT");

        var confirmed = reservations.manualAssign(STAFF, item.id(), 102, "a2");

        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.staffId()).isEqualTo(102);
        assertThat(jdbc.queryForObject("select count(*) from notification where type='VISIT_CONFIRMED'", Integer.class)).isEqualTo(1);
        assertThat(reservations.manualAssign(STAFF, item.id(), 102, "a2").id()).isEqualTo(item.id());
        assertThat(code(() -> reservations.manualAssign(STAFF, item.id(), 101, "a3"))).isEqualTo("INVALID_STATE");
    }

    @Test void anUnassignedRequestExpiresAtItsVisitTimeButAConfirmedOneNeverDoes() {
        rankingFails = true;
        var pending = reservations.create(CUSTOMER, create("e1", 1, BRANCH, slot(0)));
        rankingFails = false;
        var confirmed = reservations.create(OTHER, create("e2", 2, BRANCH, slot(1)));
        jdbc.update("update visit_reservation set starts_at = UTC_TIMESTAMP(6) - interval 1 hour");

        assertThat(reservations.expireUnassigned()).isEqualTo(1);
        assertThat(reservations.expireUnassigned()).isZero();

        assertThat(status(pending.id())).isEqualTo("EXPIRED");
        assertThat(status(confirmed.id())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from notification where type='VISIT_EXPIRED'", Integer.class)).isEqualTo(1);
        assertThat(reservations.create(CUSTOMER, create("e3", 1, BRANCH, slot(2))).status()).isEqualTo("CONFIRMED");
    }

    @Test void aChangeMovesTheBookingAndAFailedChangeKeepsTheOldOne() {
        rankingFails = false;
        var first = reservations.create(CUSTOMER, create("c1", 1, BRANCH, slot(0)));

        var moved = reservations.change(CUSTOMER, first.id(), create("c2", 1, BRANCH, slot(1)));

        assertThat(status(first.id())).isEqualTo("CANCELED");
        assertThat(moved.status()).isEqualTo("CONFIRMED");
        assertThat(moved.startsAt()).isEqualTo(slot(1).toInstant());
        assertThat(code(() -> reservations.change(CUSTOMER, moved.id(), create("c3", 1, CLOSED_BRANCH, slot(2))))).isEqualTo("RESERVATION_BRANCH_UNAVAILABLE");
        assertThat(status(moved.id())).isEqualTo("CONFIRMED");
        assertThat(code(() -> reservations.change(OTHER, moved.id(), create("c4", 2, BRANCH, slot(2))))).isEqualTo("NOT_FOUND");
    }

    @Test void manualCandidatesComeInWorkloadOrderAndTheChoiceIsRecordedAgainstThem() throws Exception {
        rankingFails = false;
        reservations.create(OTHER, create("busy", 2, BRANCH, slot(1)));
        assertThat(jdbc.queryForObject("select assigned_staff_id from visit_reservation where case_id=2", Long.class)).isEqualTo(STAFF);
        rankingFails = true;
        var item = reservations.create(CUSTOMER, create("m", 1, BRANCH, slot(0)));

        assertThat(reservations.manualCandidates(STAFF, item.id())).extracting(StaffRankingPort.Candidate::staffId).containsExactly(STAFF_2, STAFF);

        reservations.manualAssign(STAFF, item.id(), STAFF, "manual-pick");
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jdbc.queryForObject("select cast(payload as char) from event_log where request_id='manual-pick-manual'", String.class));
        assertThat(payload.path("basis").asText()).isEqualTo("MANUAL");
        assertThat(payload.path("recommended").asLong()).isEqualTo(STAFF_2);
        assertThat(payload.path("assignedWorkload").asInt()).isEqualTo(1);
        assertThat(payload.path("ranked").size()).isEqualTo(2);
        assertThat(payload.path("ranked").get(0).path("staffId").asLong()).isEqualTo(STAFF_2);
    }

    private int active(long caseId) { return jdbc.queryForObject("select count(*) from visit_reservation where case_id=? and status not in ('CANCELED','EXPIRED')", Integer.class, caseId); }
    private int notices() { return jdbc.queryForObject("select count(*) from notification", Integer.class); }
    private String held(long id) { return jdbc.queryForObject("select concat(status,'|',assigned_staff_id,'|',starts_at) from visit_reservation where id=?", String.class, id); }

    @Test void aChangeToAFullTimeKeepsTheOldBookingAndItsStaff() {
        rankingFails = false;
        var mine = reservations.create(CUSTOMER, create("m1", 1, BRANCH, slot(0)));
        jdbc.update("update service_case set result_notified_at=UTC_TIMESTAMP(6) where id=5");
        reservations.create(OTHER, create("o1", 2, BRANCH, slot(1)));
        reservations.create(CUSTOMER, create("m5", 5, BRANCH, slot(1)));
        String before = held(mine.id()); int noticesBefore = notices();

        assertThat(code(() -> reservations.change(CUSTOMER, mine.id(), create("m1-full", 1, BRANCH, slot(1))))).isEqualTo("RESERVATION_SLOT_UNAVAILABLE");

        assertThat(held(mine.id())).isEqualTo(before);
        assertThat(active(1)).isEqualTo(1);
        assertThat(notices()).isEqualTo(noticesBefore);
    }

    @Test void aDatabaseErrorAfterTheNewBookingRollsTheWholeChangeBack() {
        rankingFails = false;
        var mine = reservations.create(CUSTOMER, create("m1", 1, BRANCH, slot(0)));
        String before = held(mine.id()); int noticesBefore = notices();
        // The change's final audit insert collides on uk_event_operation, after the old row was released and the new one booked.
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,actor_type,request_id) values('TEST','0','TEST','SYSTEM','visit-change-boom')");

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> reservations.change(CUSTOMER, mine.id(), create("boom", 1, BRANCH, slot(2))));

        assertThat(held(mine.id())).isEqualTo(before);
        assertThat(active(1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from visit_reservation where case_id=1", Integer.class)).as("no new row survives").isEqualTo(1);
        assertThat(notices()).as("the confirmation notice rolled back too").isEqualTo(noticesBefore);
    }

    @Test void twoConcurrentChangesOfOneBookingLeaveExactlyOneActive() throws Exception {
        rankingFails = false;
        var mine = reservations.create(CUSTOMER, create("m1", 1, BRANCH, slot(0)));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<String>> calls = new java.util.ArrayList<>();
        for (int n = 1; n <= 2; n++) {
            int index = n;
            calls.add(pool.submit(() -> { start.await(); try { reservations.change(CUSTOMER, mine.id(), create("race-" + index, 1, BRANCH, slot(index))); return "OK"; } catch (DomainException e) { return e.code(); } }));
        }
        start.countDown();
        List<String> results = new java.util.ArrayList<>();
        for (var call : calls) results.add(call.get(60, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(results).containsOnlyOnce("OK");
        assertThat(active(1)).isEqualTo(1);
        assertThat(status(mine.id())).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("select count(*) from event_log where event_type='VISIT_CHANGED'", Integer.class)).isEqualTo(1);
    }

    @Test void aRetriedChangeReturnsTheSameBooking() {
        rankingFails = false;
        var mine = reservations.create(CUSTOMER, create("m1", 1, BRANCH, slot(0)));
        var moved = reservations.change(CUSTOMER, mine.id(), create("again", 1, BRANCH, slot(1)));

        var replay = reservations.change(CUSTOMER, mine.id(), create("again", 1, BRANCH, slot(1)));

        assertThat(replay.id()).isEqualTo(moved.id());
        assertThat(active(1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from visit_reservation where case_id=1", Integer.class)).isEqualTo(2);
    }
}
