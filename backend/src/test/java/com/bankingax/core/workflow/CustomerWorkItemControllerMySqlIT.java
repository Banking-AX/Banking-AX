package com.bankingax.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.enabled=true",
        "app.auth.jwt-secret=work-item-resume-test-secret-with-at-least-thirty-two-bytes",
        "app.auth.issuer=banking-ax-work-item-resume-test",
        "app.case-port=jpa"
})
class CustomerWorkItemControllerMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_work_item_resume_it")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    @BeforeEach
    void seed() {
        jdbc.update("delete from refresh_token");
        jdbc.update("delete from work_item");
        jdbc.update("delete from workflow");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,role,name,is_active) values(?,?,?,?,?,true)",
                1, "resume_customer_one", passwords.encode("test-password"), "CUSTOMER", "customer one");
        jdbc.update("insert into `user`(id,login_id,password_hash,role,name,is_active) values(?,?,?,?,?,true)",
                2, "resume_customer_two", passwords.encode("test-password"), "CUSTOMER", "customer two");
        jdbc.update("insert into `user`(id,login_id,password_hash,role,name,is_active) values(?,?,?,?,?,true)",
                101, "resume_staff", passwords.encode("test-password"), "STAFF", "staff");
        jdbc.update("insert into workflow(id,customer_id,status,version) values"
                + "(10,1,'WAITING_CONTINUE',0),(20,2,'WAITING_CONTINUE',0),(30,1,'WAITING_CONTINUE',0)");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,resume_step,draft_data,request_id,version) values"
                + "(100,10,'REPAYMENT_ORDER_CHANGE',1,'ON_HOLD','CONSENT',cast('{\"selectedLoanId\":501}' as json),'resume-100',0),"
                + "(200,20,'REPAYMENT_ORDER_CHANGE',1,'ON_HOLD','CONSENT',cast('{}' as json),'resume-200',0),"
                + "(300,30,'LOAN_INQUIRY',1,'ACTIVE',null,cast('{}' as json),'resume-300',0),"
                + "(301,30,'REPAYMENT_ORDER_CHANGE',2,'ON_HOLD','LOAN_SELECTED',cast('{}' as json),'resume-301',0)");
    }

    @Test
    void resumesOwnedHeldWorkAndPreservesDraftAndResumeStep() {
        String token = login("resume_customer_one");

        ResponseEntity<Map> response = resume(100, 0, token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(response)).containsEntry("workItemId", 100)
                .containsEntry("status", "ACTIVE")
                .containsEntry("version", 1)
                .containsEntry("resumeStep", "CONSENT");
        assertThat(jdbc.queryForObject("select status from workflow where id=10", String.class))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select draft_data from work_item where id=100", String.class))
                .contains("selectedLoanId").contains("501");
    }

    @Test
    void rejectsForeignStaffMissingStaleActiveAndWorkflowConflict() {
        String owner = login("resume_customer_one");
        String other = login("resume_customer_two");
        String staff = login("resume_staff");

        assertError(resume(200, 0, owner), HttpStatus.BAD_REQUEST, "WORK_ITEM_NOT_AVAILABLE");
        assertError(resume(9999, 0, owner), HttpStatus.BAD_REQUEST, "WORK_ITEM_NOT_AVAILABLE");
        assertError(resume(100, 0, staff), HttpStatus.FORBIDDEN, "AUTH_FORBIDDEN");
        assertError(resume(100, 1, owner), HttpStatus.BAD_REQUEST, "VERSION_CONFLICT");

        jdbc.update("update work_item set status='ACTIVE' where id=100");
        assertError(resume(100, 0, owner), HttpStatus.BAD_REQUEST, "INVALID_STATE");
        jdbc.update("update work_item set status='ON_HOLD' where id=100");

        assertError(resume(301, 0, owner), HttpStatus.BAD_REQUEST, "WORKFLOW_ACTIVE_EXISTS");
        assertThat(jdbc.queryForObject("select status from work_item where id=301", String.class))
                .isEqualTo("ON_HOLD");
    }

    @Test
    void requiresJwtAndExpectedVersion() throws Exception {
        var unauthenticated = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://127.0.0.1:" + port
                                + "/api/v1/customer/work-items/100/resume"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"expectedVersion\":0}"))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        ResponseEntity<Map> missing = exchange(HttpMethod.POST, "/api/v1/customer/work-items/100/resume",
                Map.of(), login("resume_customer_one"));
        assertError(missing, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    private String login(String loginId) {
        ResponseEntity<Map> response = exchange(HttpMethod.POST, "/api/v1/auth/login",
                Map.of("loginId", loginId, "password", "test-password"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) data(response).get("accessToken");
    }

    private ResponseEntity<Map> resume(long workItemId, int version, String token) {
        return exchange(HttpMethod.POST, "/api/v1/customer/work-items/" + workItemId + "/resume",
                Map.of("expectedVersion", version), token);
    }

    private ResponseEntity<Map> exchange(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return http.exchange("http://127.0.0.1:" + port + path, method,
                new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(ResponseEntity<Map> response) {
        return (String) ((Map<String, Object>) response.getBody().get("error")).get("code");
    }

    private static void assertError(ResponseEntity<Map> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(errorCode(response)).isEqualTo(code);
    }
}
