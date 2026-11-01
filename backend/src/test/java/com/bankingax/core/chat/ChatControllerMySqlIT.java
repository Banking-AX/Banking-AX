package com.bankingax.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
    "app.auth.jwt-secret=chat-controller-test-secret-with-at-least-thirty-two-bytes",
    "app.auth.issuer=banking-ax-chat-test"
})
class ChatControllerMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_chat_controller_it")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    ObjectMapper json;

    @MockBean
    CustomerAgentClient customerAgent;

    @BeforeEach
    void seedUsers() {
        reset(customerAgent);
        jdbc.update("delete from chat_message");
        jdbc.update("delete from chat_room");
        jdbc.update("delete from refresh_token");
        jdbc.update("delete from `user`");
        jdbc.update(
                "insert into `user`(id,login_id,password_hash,`role`,name,is_active) values(?,?,?,?,?,true)",
                1, "chat_customer_one", passwords.encode("test-password"), "CUSTOMER", "customer one");
        jdbc.update(
                "insert into `user`(id,login_id,password_hash,`role`,name,is_active) values(?,?,?,?,?,true)",
                2, "chat_customer_two", passwords.encode("test-password"), "CUSTOMER", "customer two");
        jdbc.update(
                "insert into `user`(id,login_id,password_hash,`role`,name,is_active) values(?,?,?,?,?,true)",
                101, "chat_staff", passwords.encode("test-password"), "STAFF", "staff");
        jdbc.update(
                "insert into `user`(id,login_id,password_hash,`role`,name,is_active) values(?,?,?,?,?,true)",
                999, "chat_admin", passwords.encode("test-password"), "ADMIN", "admin");
    }

    @Test
    void runsA02AgentWithJwtPersistsBothMessagesAndReplaysIdempotently() {
        String token = login("chat_customer_one");
        long roomId = createRoom(token);
        when(customerAgent.start(any())).thenReturn(completedAgentResponse(roomId));
        String path = "/api/v1/customer/chat/rooms/" + roomId + "/agent-runs";
        Map<String, String> body = Map.of(
                "messageRequestId", "agent-message-1",
                "operationRequestId", "agent-operation-1",
                "content", "내 대출정보 조회해 줘");

        ResponseEntity<Map> first = exchange(HttpMethod.POST, path, body, token);
        ResponseEntity<Map> replay = exchange(HttpMethod.POST, path, body, token);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(first).get("financialOperationCompleted")).isEqualTo(true);
        assertThat(data(first).get("agentMessageSaved")).isEqualTo(true);
        Map<String, Object> firstCustomer = nested(data(first), "customerMessage");
        Map<String, Object> firstAgent = nested(data(first), "agentMessage");
        assertThat(firstCustomer.get("senderType")).isEqualTo("CUSTOMER");
        assertThat(firstAgent.get("senderType")).isEqualTo("AGENT");
        assertThat(nested(data(replay), "customerMessage").get("id"))
                .isEqualTo(firstCustomer.get("id"));
        assertThat(nested(data(replay), "agentMessage").get("id"))
                .isEqualTo(firstAgent.get("id"));
        assertThat(jdbc.queryForObject(
                "select count(*) from chat_message where chat_room_id=?", Integer.class, roomId))
                .isEqualTo(2);

        ArgumentCaptor<CustomerAgentClient.AgentRequest> request =
                ArgumentCaptor.forClass(CustomerAgentClient.AgentRequest.class);
        verify(customerAgent, org.mockito.Mockito.times(2)).start(request.capture());
        assertThat(request.getAllValues()).allSatisfy(sent -> {
            assertThat(sent.requestId()).isEqualTo("agent-operation-1");
            assertThat(sent.customerId()).isEqualTo(1);
            assertThat(sent.chatRoomId()).isEqualTo(roomId);
            assertThat(sent.message()).isEqualTo("내 대출정보 조회해 줘");
            assertThat(sent.recentMessages()).doesNotContain("CUSTOMER: 내 대출정보 조회해 줘");
        });
    }

    @Test
    void keepsCustomerMessageWhenAgentCallFailsAndRejectsForeignRoom() {
        String owner = login("chat_customer_one");
        String other = login("chat_customer_two");
        long roomId = createRoom(owner);
        when(customerAgent.start(any())).thenThrow(new DomainException(
                "CUSTOMER_AGENT_UNAVAILABLE", "Customer Agent is unavailable.", true));
        Map<String, String> body = Map.of(
                "messageRequestId", "failed-message",
                "operationRequestId", "failed-operation",
                "content", "내 대출정보 조회해 줘");

        ResponseEntity<Map> failed = exchange(
                HttpMethod.POST, "/api/v1/customer/chat/rooms/" + roomId + "/agent-runs", body, owner);
        ResponseEntity<Map> foreign = exchange(
                HttpMethod.POST, "/api/v1/customer/chat/rooms/" + roomId + "/agent-runs", body, other);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(errorCode(failed)).isEqualTo("CUSTOMER_AGENT_UNAVAILABLE");
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject(
                "select count(*) from chat_message where chat_room_id=? and sender_type='CUSTOMER'",
                Integer.class, roomId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from chat_message where chat_room_id=? and sender_type='AGENT'",
                Integer.class, roomId)).isZero();
    }

    @Test
    void requiresCustomerJwtAndRestrictsEveryRoomToItsOwner() {
        assertThat(exchange(HttpMethod.GET, "/api/v1/customer/chat/rooms", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        String customerOne = login("chat_customer_one");
        String customerTwo = login("chat_customer_two");
        ResponseEntity<Map> created = exchange(
                HttpMethod.POST, "/api/v1/customer/chat/rooms", null, customerOne);
        long roomId = number(data(created).get("id"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasPath("/api/v1/customer/chat/rooms/" + roomId);
        assertThat(listData(exchange(
                        HttpMethod.GET, "/api/v1/customer/chat/rooms", null, customerOne)))
                .hasSize(1);
        assertThat(exchange(
                        HttpMethod.GET, "/api/v1/customer/chat/rooms/" + roomId, null, customerOne)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> foreign = exchange(
                HttpMethod.GET, "/api/v1/customer/chat/rooms/" + roomId, null, customerTwo);
        ResponseEntity<Map> missing = exchange(
                HttpMethod.GET, "/api/v1/customer/chat/rooms/999999", null, customerTwo);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(foreign)).isEqualTo("CHAT_ROOM_NOT_AVAILABLE");
        assertThat(errorCode(missing)).isEqualTo("CHAT_ROOM_NOT_AVAILABLE");

        assertThat(exchange(
                        HttpMethod.POST, "/api/v1/customer/chat/rooms", null, login("chat_staff"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(
                        HttpMethod.POST, "/api/v1/customer/chat/rooms", null, login("chat_admin"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void storesListsAndIdempotentlyReplaysCustomerMessages() {
        String token = login("chat_customer_one");
        long roomId = createRoom(token);
        String path = "/api/v1/customer/chat/rooms/" + roomId + "/messages";

        ResponseEntity<Map> first = exchange(
                HttpMethod.POST, path, Map.of("requestId", "http-message-1", "content", "first"), token);
        ResponseEntity<Map> second = exchange(
                HttpMethod.POST, path, Map.of("requestId", "http-message-2", "content", "second"), token);
        ResponseEntity<Map> replay = exchange(
                HttpMethod.POST, path, Map.of("requestId", "http-message-1", "content", "first"), token);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(number(data(first).get("seq"))).isEqualTo(1);
        assertThat(number(data(second).get("seq"))).isEqualTo(2);
        assertThat(number(data(replay).get("id"))).isEqualTo(number(data(first).get("id")));
        assertThat(listData(exchange(HttpMethod.GET, path, null, token)))
                .extracting(item -> number(item.get("seq")))
                .containsExactly(1L, 2L);
        assertThat(jdbc.queryForObject(
                "select count(*) from chat_message where chat_room_id=?", Integer.class, roomId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select sender_type from chat_message where chat_room_id=? and seq=1", String.class, roomId))
                .isEqualTo("CUSTOMER");
    }

    @Test
    void rejectsConflictingOrInvalidMessagesAndNeverAcceptsAnExternalSenderType() {
        String token = login("chat_customer_one");
        long roomId = createRoom(token);
        String path = "/api/v1/customer/chat/rooms/" + roomId + "/messages";
        exchange(HttpMethod.POST, path, Map.of("requestId", "conflict", "content", "original"), token);

        ResponseEntity<Map> conflict = exchange(
                HttpMethod.POST, path, Map.of("requestId", "conflict", "content", "changed"), token);
        ResponseEntity<Map> missingRequest = exchange(
                HttpMethod.POST, path, Map.of("content", "missing request"), token);
        ResponseEntity<Map> blankContent = exchange(
                HttpMethod.POST, path, Map.of("requestId", "blank", "content", "   "), token);
        ResponseEntity<Map> forgedSender = exchange(
                HttpMethod.POST,
                path,
                Map.of("requestId", "forged", "content", "still customer", "senderType", "AGENT"),
                token);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(conflict)).isEqualTo("DUPLICATE_REQUEST_CONFLICT");
        assertThat(missingRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blankContent.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(forgedSender.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(forgedSender).get("senderType")).isEqualTo("CUSTOMER");
        assertThat(jdbc.queryForObject(
                "select sender_type from chat_message where request_id='forged'", String.class))
                .isEqualTo("CUSTOMER");
    }

    private long createRoom(String token) {
        return number(data(exchange(HttpMethod.POST, "/api/v1/customer/chat/rooms", null, token)).get("id"));
    }

    private CustomerAgentClient.AgentResponse completedAgentResponse(long roomId) {
        return new CustomerAgentClient.AgentResponse(
                "agent-operation-1",
                1,
                "STARTED",
                json.createObjectNode(),
                new CustomerAgentClient.WorkflowView(501, 1001, "LOAN_INQUIRY", "ACTIVE"),
                json.createObjectNode().put("chatRoomId", roomId),
                new CustomerAgentClient.ExecutionStatus(
                        501, 1001, "LOAN_INQUIRY", "DONE", "COMPLETED"),
                "대출계좌 3건을 조회했습니다.",
                "GENERATED");
    }

    private String login(String loginId) {
        ResponseEntity<Map> response = exchange(
                HttpMethod.POST,
                "/api/v1/auth/login",
                Map.of("loginId", loginId, "password", "test-password"),
                null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) data(response).get("accessToken");
    }

    private ResponseEntity<Map> exchange(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return http.exchange(
                "http://127.0.0.1:" + port + path,
                method,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listData(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(ResponseEntity<Map> response) {
        return (String) ((Map<String, Object>) response.getBody().get("error")).get("code");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
