package com.bankingax.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bankingax.core.workflow.WorkItemStatus;
import com.bankingax.core.workflow.jpa.WorkflowStartService;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        "app.case-port=jpa",
        "app.auth.jwt-secret=b01-reconnect-test-secret-with-at-least-thirty-two-bytes",
        "app.auth.issuer=banking-ax-b01-reconnect-test"
})
class B01ReconnectControllerMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_b01_reconnect_it")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired WorkflowStartService starts;

    @BeforeEach
    void seed() {
        jdbc.update("delete from event_log");
        jdbc.update("delete from task_result");
        jdbc.update("delete from work_item");
        jdbc.update("delete from workflow");
        jdbc.update("delete from chat_message");
        jdbc.update("delete from chat_room");
        jdbc.update("delete from refresh_token");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,role,name,is_active) values(?,?,?,?,?,true)",
                1, "reconnect_customer", passwords.encode("test-password"), "CUSTOMER", "customer");
        jdbc.update("insert into `user`(id,login_id,password_hash,role,name,is_active) values(?,?,?,?,?,true)",
                2, "other_customer", passwords.encode("test-password"), "CUSTOMER", "other");
        jdbc.update("insert into `user`(id,login_id,password_hash,role,name,is_active) values(?,?,?,?,?,true)",
                3, "reconnect_staff", passwords.encode("test-password"), "STAFF", "staff");
        jdbc.update("insert into chat_room(id,customer_id) values(71,1),(72,1),(73,1),(81,2)");
    }

    @Test
    void continuesSelectedActiveItemWithoutMovingWorkflowOrChangingStatus() {
        long workItemId = prepare("continue-source", 1, 71).workItem().workItemId();
        String token = login("reconnect_customer");

        ResponseEntity<Map> response = reconnect(token, 72, Map.of(
                "requestId", "continue-click", "choice", "CONTINUE_EXISTING", "workItemId", workItemId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(response)).containsEntry("outcome", "EXISTING_SELECTED")
                .containsEntry("workItemId", (int) workItemId)
                .containsEntry("workItemStatus", "ACTIVE")
                .containsEntry("originalChatRoomId", 71)
                .containsEntry("originalChatRoomAvailable", true)
                .containsEntry("navigationTarget", "ORIGINAL_CHAT");
        assertThat(jdbc.queryForObject(
                "select chat_room_id from workflow where id=(select workflow_id from work_item where id=?)",
                Long.class, workItemId)).isEqualTo(71);
        assertThat(jdbc.queryForObject("select status from work_item where id=?", String.class, workItemId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void continuesOnHoldItemAndFallsBackToTaskWhenOriginalRoomIsUnavailable() {
        long workItemId = prepare("hold-source", 1, 71).workItem().workItemId();
        jdbc.update("update work_item set status='ON_HOLD' where id=?", workItemId);
        jdbc.update("update workflow set chat_room_id=null where id=(select workflow_id from work_item where id=?)",
                workItemId);
        String token = login("reconnect_customer");

        ResponseEntity<Map> response = reconnect(token, 72, Map.of(
                "requestId", "hold-click", "choice", "CONTINUE_EXISTING", "workItemId", workItemId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(response)).containsEntry("outcome", "EXISTING_SELECTED")
                .containsEntry("workItemStatus", "ON_HOLD")
                .containsEntry("originalChatRoomAvailable", false)
                .containsEntry("navigationTarget", "B01_TASK");
        assertThat(data(response).get("originalChatRoomId")).isNull();
        assertThat(jdbc.queryForObject("select status from work_item where id=?", String.class, workItemId))
                .isEqualTo("ON_HOLD");
    }

    @Test
    void blocksStartNewForActiveAndAllowsItWhenOnlyOnHoldRemains() {
        long existing = prepare("active-source", 1, 71).workItem().workItemId();
        String token = login("reconnect_customer");

        ResponseEntity<Map> blocked = reconnect(token, 72, Map.of(
                "requestId", "blocked-new", "choice", "START_NEW"));
        assertThat(data(blocked)).containsEntry("outcome", "ACTIVE_WORK_ITEM_CONFLICT")
                .containsEntry("workItemId", (int) existing);
        assertThat(countB01()).isOne();

        jdbc.update("update work_item set status='ON_HOLD' where id=?", existing);
        ResponseEntity<Map> created = reconnect(token, 72, Map.of(
                "requestId", "allowed-new", "choice", "START_NEW"));
        assertThat(data(created)).containsEntry("outcome", "NEW_CREATED")
                .containsEntry("navigationTarget", "START_B01_GRAPH")
                .containsEntry("originalChatRoomId", 72);
        assertThat(countB01()).isEqualTo(2);
    }

    @Test
    void startNewKeepsSameChatReusePolicy() {
        long existing = prepare("same-chat-source", 1, 72).workItem().workItemId();
        ResponseEntity<Map> response = reconnect(login("reconnect_customer"), 72, Map.of(
                "requestId", "same-chat-new", "choice", "START_NEW"));

        assertThat(data(response)).containsEntry("outcome", "SAME_CHAT_REUSED")
                .containsEntry("workItemId", (int) existing)
                .containsEntry("navigationTarget", "START_B01_GRAPH");
        assertThat(countB01()).isOne();
    }

    @Test
    void replaysSamePayloadAndRejectsRequestIdWithDifferentPayload() {
        long first = prepare("first-hold", 1, 71).workItem().workItemId();
        jdbc.update("update work_item set status='ON_HOLD' where id=?", first);
        String token = login("reconnect_customer");
        Map<String, Object> body = Map.of("requestId", "idempotent-click", "choice", "START_NEW");

        ResponseEntity<Map> created = reconnect(token, 72, body);
        ResponseEntity<Map> replay = reconnect(token, 72, body);
        ResponseEntity<Map> conflict = reconnect(token, 72, Map.of(
                "requestId", "idempotent-click", "choice", "CONTINUE_EXISTING", "workItemId", first));

        assertThat(data(replay)).isEqualTo(data(created));
        assertThat(countB01()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from event_log where event_type='B01_RECONNECT_SELECTED'",
                Integer.class)).isOne();
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(conflict)).isEqualTo("DUPLICATE_REQUEST_CONFLICT");
    }

    @Test
    void rejectsForeignEndedAndForeignRoomAccess() {
        long foreign = prepare("foreign-source", 2, 81).workItem().workItemId();
        long ended = prepare("ended-source", 1, 71).workItem().workItemId();
        jdbc.update("update work_item set status='DONE' where id=?", ended);
        String token = login("reconnect_customer");

        ResponseEntity<Map> foreignItem = reconnect(token, 72, Map.of(
                "requestId", "foreign-item", "choice", "CONTINUE_EXISTING", "workItemId", foreign));
        ResponseEntity<Map> endedItem = reconnect(token, 72, Map.of(
                "requestId", "ended-item", "choice", "CONTINUE_EXISTING", "workItemId", ended));
        ResponseEntity<Map> foreignRoom = reconnect(token, 81, Map.of(
                "requestId", "foreign-room", "choice", "START_NEW"));

        assertThat(errorCode(foreignItem)).isEqualTo("WORK_ITEM_NOT_AVAILABLE");
        assertThat(errorCode(endedItem)).isEqualTo("WORK_ITEM_NOT_AVAILABLE");
        assertThat(foreignRoom.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(foreignRoom)).isEqualTo("CHAT_ROOM_NOT_AVAILABLE");
    }

    @Test
    void concurrentIdenticalStartNewCreatesOneWorkItemAndOneReceipt() throws Exception {
        long held = prepare("concurrent-hold", 1, 71).workItem().workItemId();
        jdbc.update("update work_item set status='ON_HOLD' where id=?", held);
        String token = login("reconnect_customer");
        Map<String, Object> body = Map.of("requestId", "concurrent-new", "choice", "START_NEW");
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { gate.await(); return reconnect(token, 72, body); });
            var second = pool.submit(() -> { gate.await(); return reconnect(token, 72, body); });
            gate.countDown();

            assertThat(data(first.get(15, TimeUnit.SECONDS)))
                    .isEqualTo(data(second.get(15, TimeUnit.SECONDS)));
            assertThat(countB01()).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "select count(*) from event_log where event_type='B01_RECONNECT_SELECTED'",
                    Integer.class)).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void validatesRequestShapeAndRequiresCustomerJwt() {
        String token = login("reconnect_customer");
        ResponseEntity<Map> missingWorkItem = reconnect(token, 72, Map.of(
                "requestId", "missing-item", "choice", "CONTINUE_EXISTING"));
        ResponseEntity<Map> extraWorkItem = reconnect(token, 72, Map.of(
                "requestId", "extra-item", "choice", "START_NEW", "workItemId", 10));
        ResponseEntity<Map> staffJwt = reconnect(login("reconnect_staff"), 72, Map.of(
                "requestId", "staff-jwt", "choice", "START_NEW"));

        assertThat(missingWorkItem.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(extraWorkItem.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(staffJwt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private WorkflowStartService.B01PrepareResult prepare(String requestId, long customerId, long roomId) {
        return starts.prepareB01(new WorkflowStartService.B01PrepareCommand(
                requestId, customerId, roomId, WorkflowStartService.B01PrepareMode.DISCOVER, null));
    }

    private int countB01() {
        return jdbc.queryForObject(
                "select count(*) from work_item where task_code='REPAYMENT_ORDER_CHANGE'", Integer.class);
    }

    private ResponseEntity<Map> reconnect(String token, long roomId, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return http.exchange("http://127.0.0.1:" + port
                        + "/api/v1/customer/chat/rooms/" + roomId + "/b01/reconnect",
                HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private String login(String loginId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = http.exchange("http://127.0.0.1:" + port + "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("loginId", loginId, "password", "test-password"), headers),
                Map.class);
        return (String) data(response).get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(ResponseEntity<Map> response) {
        return (String) ((Map<String, Object>) response.getBody().get("error")).get("code");
    }
}
