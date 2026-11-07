package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.notification.NotificationWriter;
import com.bankingax.core.reservation.MockStaffRankingAdapter;
import com.bankingax.core.reservation.VisitReservationController;
import com.bankingax.core.reservation.VisitReservationService;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Runs the real T2 lifecycle/reservation SQL against a disposable, Flyway-migrated schema when Testcontainers
 * cannot reach Docker. Never point T2_VERIFY_DB_URL at the shared banking_ax database: every test truncates.
 */
@EnabledIfEnvironmentVariable(named = "T2_VERIFY_DB_URL", matches = ".*banking_ax_t2_verify.*")
class T2DemoFlowLocalMySqlIT {
    private JdbcTemplate jdbc;
    private C02StaffDecisionPersistence persistence;

    @BeforeEach
    void seed() {
        DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("T2_VERIFY_DB_URL"),
                System.getenv("APP_DB_USERNAME"), System.getenv("APP_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        persistence = new C02StaffDecisionPersistence(jdbc);
        // foreign_key_checks is per session, so the whole reset runs on one connection.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks=0");
                for (String table : List.of("event_log", "notification", "visit_reservation", "review_item", "agent_review_run", "staff_review", "case_revision", "service_case", "work_item", "workflow", "`user`", "branch"))
                    statement.execute("truncate table " + table);
                statement.execute("set foreign_key_checks=1");
            }
            return null;
        });
        jdbc.update("insert into branch(id,branch_code,name,address) values(1,'T-1','테스트 지점','서울')");
        jdbc.update("insert into `user`(id,login_id,password_hash,role,name,branch_id,can_pre_review) values(1,'c','x','CUSTOMER','고객',null,false),(101,'s1','x','STAFF','직원1',1,true),(102,'s2','x','STAFF','직원2',1,true)");
        jdbc.update("insert into workflow(id,customer_id,status) values(1,1,'RUNNING')");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values(6,1,'INHERITANCE_DEPOSIT',1,'HANDED_OFF','wi-6')");
        jdbc.update("insert into service_case(id,work_item_id,customer_id,case_type,case_status,review_status,revision,visit_required,application) values(2,6,1,'INHERITANCE_DEPOSIT','SUBMITTED','READY_FOR_STAFF',1,true,'{}')");
        jdbc.update("insert into case_revision(case_id,revision,application_snapshot,change_reason) values(2,1,'{}','SUBMIT')");
    }

    private String status() { return jdbc.queryForObject("select case_status from service_case where id=2", String.class); }

    @Test
    void reviewStartBlocksCancelAndTheDecisionClosesTheOpenReview() {
        assertEquals("IN_REVIEW", persistence.startReview(101, 2, 1, "start-1").caseStatus());
        assertEquals("IN_REVIEW", persistence.startReview(101, 2, 1, "start-1").caseStatus(), "same requestId replays");
        DomainException cancel = assertThrows(DomainException.class, () -> persistence.cancelByCustomer(1, 2, "cancel-1"));
        assertEquals("CASE_CANCEL_NOT_ALLOWED", cancel.code());
        persistence.persist(102, 2, 1, "decide-1", "PROCEED", "방문 전 확인을 마쳤어요.", "REVIEW_PASSED");
        assertEquals("REVIEW_PASSED", status());
        assertEquals(1, jdbc.queryForObject("select count(*) from staff_review where case_id=2", Integer.class));
        assertEquals(102L, jdbc.queryForObject("select staff_id from staff_review where case_id=2 and decided_at is not null", Long.class));
        assertEquals("IN_REVIEW", jdbc.queryForObject("select from_value from event_log where request_id='decide-1'", String.class));
        assertEquals("CASE_APPROVED", jdbc.queryForObject("select type from notification where case_id=2", String.class));
    }

    @Test
    void cancelBeforeReviewWinsAndReviewCannotStartAfterwards() {
        assertEquals("CANCELED", persistence.cancelByCustomer(1, 2, "cancel-2").caseStatus());
        assertEquals("INVALID_STATE", assertThrows(DomainException.class, () -> persistence.startReview(101, 2, 1, "start-2")).code());
        assertEquals("NOT_FOUND", assertThrows(DomainException.class, () -> persistence.cancelByCustomer(99, 2, "cancel-other")).code());
    }

    @Test
    void concurrentStartAndCancelLetExactlyOneSucceed() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<String>> calls = List.of(
                () -> { go.await(); return persistence.startReview(101, 2, 1, "race-start").caseStatus(); },
                () -> { go.await(); return persistence.cancelByCustomer(1, 2, "race-cancel").caseStatus(); });
        var pool = Executors.newFixedThreadPool(2);
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> call : calls) futures.add(pool.submit(call));
        go.countDown();
        int succeeded = 0;
        for (Future<String> future : futures) { try { future.get(); succeeded++; } catch (java.util.concurrent.ExecutionException ignored) { } }
        pool.shutdown();
        assertEquals(1, succeeded);
    }

    /** 10:00 in Seoul on the next weekday at least two days away. */
    private static OffsetDateTime offeredTime() {
        java.time.LocalDate date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(2);
        while (date.getDayOfWeek().getValue() > 5) date = date.plusDays(1);
        return date.atTime(10, 0).atOffset(java.time.ZoneOffset.ofHours(9));
    }

    @Test
    void reservationAssignsLowestWorkloadLogsTheBasisAndNotifiesTheCustomer() {
        persistence.persist(101, 2, 1, "decide-r", "PROCEED", "진행 가능", "REVIEW_PASSED");
        // 101 already has a future visit, so the workload ranking must pick 102.
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values(7,1,'INHERITANCE_DEPOSIT',2,'HANDED_OFF','wi-7')");
        jdbc.update("insert into service_case(id,work_item_id,customer_id,case_type,case_status,review_status,revision,visit_required,application) values(3,7,1,'INHERITANCE_DEPOSIT','REVIEW_PASSED','READY_FOR_STAFF',1,true,'{}')");
        jdbc.update("insert into visit_reservation(case_id,customer_id,branch_id,assigned_staff_id,starts_at,status) values(3,1,1,101,UTC_TIMESTAMP(6)+interval 3 day,'CONFIRMED')");
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("writer", new NotificationWriter(jdbc));
        VisitReservationController controller = new VisitReservationController(new VisitReservationService(jdbc, new MockStaffRankingAdapter(), beans.getBeanProvider(NotificationWriter.class)), new InternalGatewayAuthenticator("t"));
        var item = controller.create(1, ActorRoleCustomer.CUSTOMER, "t", new VisitReservationController.Create("reserve-1", 2, 1, null, offeredTime())).data();
        assertEquals(102L, item.staffId());
        assertEquals("CONFIRMED", item.status());
        assertEquals("TASK_CODE_AND_WORKLOAD", jdbc.queryForObject("select JSON_UNQUOTE(JSON_EXTRACT(payload,'$.basis')) from event_log where request_id='reserve-1-assign'", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from notification where type='VISIT_CONFIRMED' and case_id=2", Integer.class));
        assertEquals("RESERVATION_ALREADY_EXISTS", assertThrows(DomainException.class, () -> controller.create(1, ActorRoleCustomer.CUSTOMER, "t",
                new VisitReservationController.Create("reserve-again", 2, 1, null, offeredTime().plusHours(1)))).code());
        assertEquals("RESERVATION_SLOT_UNAVAILABLE", assertThrows(DomainException.class, () -> controller.create(1, ActorRoleCustomer.CUSTOMER, "t",
                new VisitReservationController.Create("reserve-past", 2, 1, null, OffsetDateTime.now().minusHours(1)))).code());
    }

    @Test
    void reservationCancelIsLockedWithin24Hours() {
        jdbc.update("update service_case set case_status='REVIEW_PASSED', result_notified_at=UTC_TIMESTAMP(6) where id=2");
        jdbc.update("insert into visit_reservation(id,case_id,customer_id,branch_id,assigned_staff_id,starts_at,status) values(50,2,1,1,101,UTC_TIMESTAMP(6)+interval 23 hour,'CONFIRMED')");
        VisitReservationController controller = new VisitReservationController(new VisitReservationService(jdbc, new MockStaffRankingAdapter(), new StaticListableBeanFactory().getBeanProvider(NotificationWriter.class)), new InternalGatewayAuthenticator("t"));
        assertEquals("RESERVATION_CHANGE_LOCKED", assertThrows(DomainException.class, () -> controller.cancel(50, 1, ActorRoleCustomer.CUSTOMER, "t", "c-23h")).code());
        jdbc.update("update visit_reservation set starts_at=UTC_TIMESTAMP(6)+interval 25 hour where id=50");
        controller.cancel(50, 1, ActorRoleCustomer.CUSTOMER, "t", "c-25h");
        assertEquals("CANCELED", jdbc.queryForObject("select status from visit_reservation where id=50", String.class));
    }

    /** Short alias so the long controller signatures stay readable. */
    private static final class ActorRoleCustomer { static final com.bankingax.core.tool.ActorRole CUSTOMER = com.bankingax.core.tool.ActorRole.CUSTOMER; }
}
