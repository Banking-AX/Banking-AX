package com.bankingax.core.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankingax.core.api.ApiExceptionHandler;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises employee-unassigned visit requests on the existing V9/V10 schema. */
@Testcontainers
class VisitReservationMySqlIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_reservation_it").withUsername("test").withPassword("test");

    static JdbcTemplate jdbc;
    static MockMvc http;
    static ObjectMapper json;
    static VisitReservationController controller;
    static TransactionTemplate transactions;

    @BeforeAll static void migrateAndSeed() {
        String jdbcUrl = MYSQL.getJdbcUrl();
        String url = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        var dataSource = new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        json = new ObjectMapper().findAndRegisterModules();

        jdbc.update("insert into branch(id,branch_code,name,address,is_active) values(10,'RSV-10','Synthetic branch','Test address',true)");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,branch_id,is_active) values " +
                "(1,'reservation_customer_1','test','CUSTOMER','Synthetic customer 1',null,true)," +
                "(2,'reservation_customer_2','test','CUSTOMER','Synthetic customer 2',null,true)," +
                "(3,'reservation_staff_3','test','STAFF','Synthetic staff',10,true)");
        jdbc.update("insert into workflow(id,customer_id,status) values(1,1,'WAITING_CONTINUE'),(2,2,'WAITING_CONTINUE')");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values " +
                "(1,1,'INHERITANCE_DEPOSIT',1,'HANDED_OFF','reservation-work-1')," +
                "(2,2,'INHERITANCE_DEPOSIT',1,'HANDED_OFF','reservation-work-2')");
        jdbc.update("insert into service_case(id,work_item_id,customer_id,case_type,case_status,review_status,revision,visit_required,application,result_notified_at) values " +
                "(100,1,1,'INHERITANCE_DEPOSIT','REVIEW_PASSED','READY_FOR_STAFF',0,true,'{}',utc_timestamp(6))," +
                "(101,2,2,'INHERITANCE_DEPOSIT','REVIEW_PASSED','READY_FOR_STAFF',0,true,'{}',utc_timestamp(6))");
        jdbc.update("insert into case_revision(case_id,revision,application_snapshot,change_reason) values " +
                "(100,0,'{}','TEST'),(101,0,'{}','TEST')");
        jdbc.update("insert into staff_review(case_id,staff_id,revision,started_at,decided_at,result) values " +
                "(100,3,0,utc_timestamp(6),utc_timestamp(6),'PROCEED'),(101,3,0,utc_timestamp(6),utc_timestamp(6),'PROCEED')");
        controller = new VisitReservationController(jdbc, new InternalGatewayAuthenticator("reservation-test-token"));
        http = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @BeforeEach void clearReservationRows() {
        jdbc.update("delete from visit_reservation");
        jdbc.update("delete from event_log where entity_type='VISIT_RESERVATION'");
        jdbc.execute("alter table visit_reservation auto_increment=1");
    }

    @Test void create_and_reschedule_persist_pending_assignment_without_claiming_staff_confirmation() throws Exception {
        OffsetDateTime startsAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(30).withNano(0);
        String create = json.writeValueAsString(new VisitReservationController.Create("reserve-create-1", 100, 10, startsAt));
        http.perform(post("/api/v1/reservations").headers(customerHeaders(1)).contentType("application/json").content(create))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_ASSIGNMENT"))
                .andExpect(jsonPath("$.data.staffId").value(org.hamcrest.Matchers.nullValue()));
        long reservationId = jdbc.queryForObject("select id from visit_reservation where case_id=100", Long.class);
        assertThat(jdbc.queryForObject("select status from visit_reservation where id=?", String.class, reservationId)).isEqualTo("PENDING_ASSIGNMENT");
        assertThat(jdbc.queryForObject("select assigned_staff_id from visit_reservation where id=?", Long.class, reservationId)).isNull();

        String update = json.writeValueAsString(new VisitReservationController.Reschedule("reserve-update-1", 10, startsAt.plusHours(4)));
        http.perform(put("/api/v1/reservations/" + reservationId).headers(customerHeaders(1))
                        .contentType("application/json").content(update))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_ASSIGNMENT"));
        assertThat(jdbc.queryForObject("select starts_at from visit_reservation where id=?", Timestamp.class, reservationId).toInstant())
                .isEqualTo(startsAt.plusHours(4).toInstant());
        assertThat(jdbc.queryForObject("select count(*) from event_log where entity_type='VISIT_RESERVATION'", Integer.class)).isEqualTo(2);
    }

    @Test void active_branch_time_conflict_is_rejected_and_canceled_slot_can_be_reused() throws Exception {
        OffsetDateTime startsAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(30).withNano(0);
        String first = json.writeValueAsString(new VisitReservationController.Create("slot-first", 100, 10, startsAt));
        http.perform(post("/api/v1/reservations").headers(customerHeaders(1)).contentType("application/json").content(first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_ASSIGNMENT"));
        String duplicate = json.writeValueAsString(new VisitReservationController.Create("slot-second", 101, 10, startsAt));
        String rebook = json.writeValueAsString(new VisitReservationController.Create("slot-rebook", 101, 10, startsAt));
        http.perform(post("/api/v1/reservations").headers(customerHeaders(2)).contentType("application/json").content(rebook))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("RESERVATION_SLOT_UNAVAILABLE"));
        http.perform(post("/api/v1/reservations/1/cancel?requestId=slot-cancel").headers(customerHeaders(1)))
                .andExpect(status().isOk());
        http.perform(post("/api/v1/reservations").headers(customerHeaders(2)).contentType("application/json").content(duplicate))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from visit_reservation where branch_id=10 and starts_at=? and status<>'CANCELED'",
                Integer.class, Timestamp.from(startsAt.toInstant()))).isEqualTo(1);
    }

    @Test void concurrent_requests_for_same_branch_time_allow_only_one_active_request() throws Exception {
        OffsetDateTime startsAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(36).withNano(0);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> actions = List.of(
                    () -> createInTransaction(1, new VisitReservationController.Create("parallel-a", 100, 10, startsAt)),
                    () -> createInTransaction(2, new VisitReservationController.Create("parallel-b", 101, 10, startsAt)));
            var results = pool.invokeAll(actions);
            List<String> outcomes = List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter("RESERVATION_SLOT_UNAVAILABLE"::equals).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from visit_reservation where branch_id=10 and starts_at=? and status<>'CANCELED'",
                    Integer.class, Timestamp.from(startsAt.toInstant()))).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void identical_request_id_replays_same_booking_but_conflicting_payload_is_rejected() throws Exception {
        OffsetDateTime startsAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(30).withNano(0);
        String original = json.writeValueAsString(new VisitReservationController.Create("same-request", 100, 10, startsAt));
        http.perform(post("/api/v1/reservations").headers(customerHeaders(1)).contentType("application/json").content(original))
                .andExpect(status().isOk());
        http.perform(post("/api/v1/reservations").headers(customerHeaders(1)).contentType("application/json").content(original))
                .andExpect(status().isOk());
        String changed = json.writeValueAsString(new VisitReservationController.Create("same-request", 100, 10, startsAt.plusHours(1)));
        http.perform(post("/api/v1/reservations").headers(customerHeaders(1)).contentType("application/json").content(changed))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("DUPLICATE_REQUEST_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from visit_reservation where case_id=100", Integer.class)).isEqualTo(1);
    }

    @Test void reservation_requires_current_staff_proceed_and_customer_notification() throws Exception {
        OffsetDateTime startsAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(30).withNano(0);
        jdbc.update("update staff_review set result='DOCUMENT_INSUFFICIENT' where case_id=100 and revision=0");
        String request = json.writeValueAsString(new VisitReservationController.Create("no-proceed", 100, 10, startsAt));
        http.perform(post("/api/v1/reservations").headers(customerHeaders(1)).contentType("application/json").content(request))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("RESERVATION_NOT_ALLOWED"));
        jdbc.update("update staff_review set result='PROCEED' where case_id=100 and revision=0");
        jdbc.update("update service_case set result_notified_at=NULL where id=100");
        String notNotified = json.writeValueAsString(new VisitReservationController.Create("not-notified", 100, 10, startsAt));
        http.perform(post("/api/v1/reservations").headers(customerHeaders(1)).contentType("application/json").content(notNotified))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("RESERVATION_NOT_ALLOWED"));
        jdbc.update("update service_case set result_notified_at=utc_timestamp(6) where id=100");
        assertThat(jdbc.queryForObject("select count(*) from visit_reservation", Integer.class)).isZero();
    }

    @Test void customer_can_only_select_their_eligible_case_and_active_branches() throws Exception {
        http.perform(get("/api/v1/reservations/eligible-cases?requestId=eligible-1").headers(customerHeaders(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].caseId").value(100))
                .andExpect(jsonPath("$.data[0].taskCode").value("INHERITANCE_DEPOSIT"));
        http.perform(get("/api/v1/reservations/branches?requestId=branches-1").headers(customerHeaders(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].branchId").value(10));
    }

    @Test void v10_releases_active_case_and_employee_slot_keys_on_cancel_and_preserves_history() {
        OffsetDateTime startsAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(30).withNano(0);
        long firstId = insertReservation(100, 1, 3, startsAt);
        assertThatThrownBy(() -> insertReservation(100, 1, 3, startsAt)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update visit_reservation set status='CANCELED',version=version+1 where id=?", firstId);
        long secondId = insertReservation(100, 1, 3, startsAt);
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(jdbc.queryForObject("select count(*) from visit_reservation where case_id=100", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from visit_reservation where active_case_id=100", Integer.class)).isEqualTo(1);
    }

    @Test void cancellation_is_owner_checked_and_rejected_inside_the_24_hour_window() throws Exception {
        long id = insertReservation(100, 1, 3, OffsetDateTime.now(ZoneOffset.UTC).plusHours(23).withNano(0));
        http.perform(post("/api/v1/reservations/" + id + "/cancel?requestId=foreign-cancel").headers(customerHeaders(2)))
                .andExpect(status().isNotFound());
        http.perform(post("/api/v1/reservations/" + id + "/cancel?requestId=too-late").headers(customerHeaders(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("RESERVATION_CHANGE_CUTOFF"));
        assertThat(jdbc.queryForObject("select status from visit_reservation where id=?", String.class, id)).isEqualTo("CONFIRMED");
    }

    private static long insertReservation(long caseId, long customerId, long staffId, OffsetDateTime startsAt) {
        jdbc.update("insert into visit_reservation(case_id,customer_id,branch_id,assigned_staff_id,starts_at,status) values(?, ?,10,?,?, 'CONFIRMED')",
                caseId, customerId, staffId, Timestamp.from(startsAt.toInstant()));
        return jdbc.queryForObject("select id from visit_reservation where case_id=? and status<>'CANCELED'", Long.class, caseId);
    }

    private static String createInTransaction(long customerId, VisitReservationController.Create request) {
        return transactions.execute(status -> {
            try {
                controller.create(customerId, ActorRole.CUSTOMER, "reservation-test-token", request);
                return "OK";
            } catch (com.bankingax.core.api.DomainException rejected) { return rejected.code(); }
        });
    }

    private static org.springframework.http.HttpHeaders customerHeaders(long actorId) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Actor-Id", Long.toString(actorId));
        headers.set("X-Actor-Role", ActorRole.CUSTOMER.name());
        headers.set("X-Tool-Gateway-Token", "reservation-test-token");
        return headers;
    }
}
