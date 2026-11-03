package com.bankingax.core.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Signed-login JWT and customer BFF HTTP flow over the Flyway-managed MySQL schema. */
@Testcontainers
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.auth.enabled=true",
        "app.auth.jwt-secret=test-only-reservation-jwt-secret-long-enough-for-hs256",
        "app.auth.issuer=banking-ax-reservation-http-it",
        "app.auth.cookie-secure=false",
        "app.case-port=jpa",
        "app.tool-gateway-token=test-internal-reservation-token"
})
class CustomerReservationJwtMySqlHttpIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_reservation_jwt_it").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @BeforeEach
    void seedNotifiedCases() {
        jdbc.update("delete from refresh_token");
        jdbc.update("delete from event_log where entity_type='VISIT_RESERVATION'");
        jdbc.update("delete from visit_reservation");
        jdbc.update("delete from staff_review");
        jdbc.update("delete from case_revision");
        jdbc.update("delete from service_case");
        jdbc.update("delete from work_item");
        jdbc.update("delete from workflow");
        jdbc.update("delete from `user`");
        jdbc.update("delete from branch");

        jdbc.update("insert into branch(id,branch_code,name,address,is_active) values(10,'RSV-JWT-10','Synthetic branch','Test address',true)");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,branch_id,is_active) values " +
                        "(1,'reservation_jwt_customer_1',?,'CUSTOMER','Synthetic customer 1',null,true)," +
                        "(2,'reservation_jwt_customer_2',?,'CUSTOMER','Synthetic customer 2',null,true)," +
                        "(3,'reservation_jwt_staff',?,'STAFF','Synthetic staff',10,true)",
                passwords.encode("customer-one-password"), passwords.encode("customer-two-password"), passwords.encode("staff-password"));
        jdbc.update("insert into workflow(id,customer_id,status) values(1,1,'WAITING_CONTINUE'),(2,2,'WAITING_CONTINUE')");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values " +
                        "(101,1,'INHERITANCE_DEPOSIT',1,'HANDED_OFF','reservation-jwt-work-101')," +
                        "(102,2,'INHERITANCE_DEPOSIT',1,'HANDED_OFF','reservation-jwt-work-102')");
        String application = "{}";
        jdbc.update("insert into service_case(id,work_item_id,customer_id,case_type,case_status,review_status,revision,visit_required,application,result_notified_at) values " +
                        "(201,101,1,'INHERITANCE_DEPOSIT','REVIEW_PASSED','READY_FOR_STAFF',0,true,cast(? as json),utc_timestamp(6))," +
                        "(202,102,2,'INHERITANCE_DEPOSIT','REVIEW_PASSED','READY_FOR_STAFF',0,true,cast(? as json),utc_timestamp(6))",
                application, application);
        jdbc.update("insert into case_revision(case_id,revision,application_snapshot,change_reason) values(201,0,cast(? as json),'TEST'),(202,0,cast(? as json),'TEST')",
                application, application);
        jdbc.update("insert into staff_review(case_id,staff_id,revision,started_at,decided_at,result) values " +
                        "(201,3,0,utc_timestamp(6),utc_timestamp(6),'PROCEED'),(202,3,0,utc_timestamp(6),utc_timestamp(6),'PROCEED')");
    }

    @Test
    void signed_customer_login_create_list_reschedule_cancel_and_rebook_keep_pending_status_and_case_link() throws Exception {
        String customer = login("reservation_jwt_customer_1", "customer-one-password", "CUSTOMER");
        String other = login("reservation_jwt_customer_2", "customer-two-password", "CUSTOMER");
        OffsetDateTime firstTime = futureAt(38);

        http.perform(get("/api/v1/customer/reservations?requestId=unauthenticated-list"))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/customer/reservations/eligible-cases?requestId=eligible-jwt")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].caseId").value(201))
                .andExpect(jsonPath("$.data[0].taskCode").value("INHERITANCE_DEPOSIT"));
        http.perform(get("/api/v1/customer/reservations/branches?requestId=branches-jwt")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].branchId").value(10));

        MvcResult created = http.perform(post("/api/v1/customer/reservations").header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create("jwt-reserve-create", 201, firstTime))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_ASSIGNMENT"))
                .andExpect(jsonPath("$.data.staffId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.caseId").value(201))
                .andExpect(jsonPath("$.data.workItemId").value(101))
                .andExpect(jsonPath("$.data.taskCode").value("INHERITANCE_DEPOSIT")).andReturn();
        long reservationId = json.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        assertThat(jdbc.queryForObject("select count(*) from visit_reservation v join service_case c on c.id=v.case_id " +
                "join work_item w on w.id=c.work_item_id where v.id=? and v.customer_id=1 and v.case_id=201 " +
                "and w.task_code='INHERITANCE_DEPOSIT' and v.status='PENDING_ASSIGNMENT'", Integer.class, reservationId)).isEqualTo(1);

        // A fresh request to the persisted list endpoint models browser reload/re-entry.
        http.perform(get("/api/v1/customer/reservations?requestId=reload-list")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(reservationId))
                .andExpect(jsonPath("$.data[0].caseId").value(201))
                .andExpect(jsonPath("$.data[0].workItemId").value(101))
                .andExpect(jsonPath("$.data[0].taskCode").value("INHERITANCE_DEPOSIT"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING_ASSIGNMENT"));
        http.perform(get("/api/v1/customer/reservations?requestId=other-list")
                        .header("Authorization", bearer(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));

        OffsetDateTime changedTime = futureAt(44);
        http.perform(put("/api/v1/customer/reservations/" + reservationId).header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(reschedule("foreign-change", changedTime))))
                .andExpect(status().isNotFound());
        MvcResult changed = http.perform(put("/api/v1/customer/reservations/" + reservationId).header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(reschedule("jwt-reserve-change", changedTime))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_ASSIGNMENT"))
                .andExpect(jsonPath("$.data.caseId").value(201))
                .andExpect(jsonPath("$.data.workItemId").value(101))
                .andExpect(jsonPath("$.data.taskCode").value("INHERITANCE_DEPOSIT")).andReturn();
        JsonNode changedData = json.readTree(changed.getResponse().getContentAsString()).path("data");
        assertThat(changedData.path("version").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from visit_reservation where id=? and case_id=201 and branch_id=10 and status='PENDING_ASSIGNMENT' and version=1",
                Integer.class, reservationId)).isEqualTo(1);

        http.perform(post("/api/v1/customer/reservations/" + reservationId + "/cancel?requestId=foreign-cancel")
                        .header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        http.perform(post("/api/v1/customer/reservations/" + reservationId + "/cancel?requestId=jwt-reserve-cancel")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from visit_reservation where id=?", String.class, reservationId)).isEqualTo("CANCELED");

        http.perform(post("/api/v1/customer/reservations").header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create("jwt-reserve-rebook", 201, firstTime))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_ASSIGNMENT"))
                .andExpect(jsonPath("$.data.caseId").value(201));
        assertThat(jdbc.queryForObject("select count(*) from visit_reservation where case_id=201", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from visit_reservation where active_case_id=201", Integer.class)).isEqualTo(1);
    }

    @Test
    void cutoff_and_concurrent_branch_slot_requests_are_enforced_through_signed_jwt_http() throws Exception {
        String customer = login("reservation_jwt_customer_1", "customer-one-password", "CUSTOMER");
        String other = login("reservation_jwt_customer_2", "customer-two-password", "CUSTOMER");
        OffsetDateTime future = futureAt(36);
        MvcResult created = http.perform(post("/api/v1/customer/reservations").header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create("cutoff-create", 201, future))))
                .andExpect(status().isOk()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        jdbc.update("update visit_reservation set starts_at=utc_timestamp(6)+interval 23 hour where id=?", id);
        http.perform(post("/api/v1/customer/reservations/" + id + "/cancel?requestId=cutoff-cancel")
                        .header("Authorization", bearer(customer)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("RESERVATION_CHANGE_CUTOFF"));
        assertThat(jdbc.queryForObject("select status from visit_reservation where id=?", String.class, id)).isEqualTo("PENDING_ASSIGNMENT");

        OffsetDateTime contested = futureAt(52);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> http.perform(post("/api/v1/customer/reservations").header("Authorization", bearer(customer))
                                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create("parallel-jwt-a", 201, contested))))
                            .andReturn().getResponse().getStatus(),
                    () -> http.perform(post("/api/v1/customer/reservations").header("Authorization", bearer(other))
                                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create("parallel-jwt-b", 202, contested))))
                            .andReturn().getResponse().getStatus());
            var results = pool.invokeAll(calls);
            List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
            assertThat(statuses.stream().filter(value -> value == 200).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(value -> value == 400).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from visit_reservation where branch_id=10 and starts_at=? and status<>'CANCELED'",
                    Integer.class, java.sql.Timestamp.from(contested.toInstant()))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private VisitReservationController.Create create(String requestId, long caseId, OffsetDateTime at) {
        return new VisitReservationController.Create(requestId, caseId, 10, at);
    }

    private VisitReservationController.Reschedule reschedule(String requestId, OffsetDateTime at) {
        return new VisitReservationController.Reschedule(requestId, 10, at);
    }

    private String login(String loginId, String password, String expectedRole) throws Exception {
        MvcResult result = http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value(expectedRole)).andReturn();
        JsonNode root = json.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("accessToken").asText();
    }

    private static OffsetDateTime futureAt(long hours) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusHours(hours).withNano(0);
    }

    private static String bearer(String jwt) { return "Bearer " + jwt; }
}
