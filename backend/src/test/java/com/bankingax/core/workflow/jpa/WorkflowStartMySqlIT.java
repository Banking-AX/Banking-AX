package com.bankingax.core.workflow.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.TaskHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {"app.case-port=jpa", "app.workflow-start-enabled=true",
        "app.tool-gateway-token=workflow-start-token"})
class WorkflowStartMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_workflow_start_it").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WorkflowStartService starts;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc http;
    @SpyBean TaskHandler tasks;

    @BeforeEach
    void seed() {
        reset(tasks);
        jdbc.update("delete from work_item");
        jdbc.update("delete from workflow");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) "
                + "values(1,'workflow-customer','x','CUSTOMER','customer')");
    }

    @AfterEach
    void clearSpy() {
        reset(tasks);
    }

    @Test
    void startsA02WorkflowAndReplaysTheSameRequest() throws Exception {
        String body = "{\"requestId\":\"a02-start\",\"customerId\":1,\"taskCode\":\"LOAN_INQUIRY\"}";

        http.perform(post("/api/v1/tools/workflows/start").headers(headers()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("a02-start"))
                .andExpect(jsonPath("$.taskCode").value("LOAN_INQUIRY"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        http.perform(post("/api/v1/tools/workflows/start").headers(headers()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workflowId").isNumber())
                .andExpect(jsonPath("$.data.workItemId").isNumber());

        assertEquals(1, count("select count(*) from workflow"));
        assertEquals(1, count("select count(*) from work_item where status='ACTIVE'"));
        assertEquals(1, count("select count(*) from work_item where request_id='a02-start'"));
    }

    @Test
    void concurrentDuplicateReturnsOneWorkflowAndOneWorkItem() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { gate.await(); return starts.start(command("a02-concurrent")); });
            var second = pool.submit(() -> { gate.await(); return starts.start(command("a02-concurrent")); });
            gate.countDown();

            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, count("select count(*) from workflow"));
            assertEquals(1, count("select count(*) from work_item where request_id='a02-concurrent'"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidTaskCodeBeforeCreatingAnything() throws Exception {
        http.perform(post("/api/v1/tools/workflows/start").headers(headers()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"bad-task\",\"customerId\":1,\"taskCode\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TASK_CODE"));
        assertEquals(0, count("select count(*) from workflow"));
        assertEquals(0, count("select count(*) from work_item"));
    }

    @Test
    void rollsBackWorkflowWhenWorkItemStartFails() {
        doThrow(new DomainException("START_FAILED", "fixture failure")).when(tasks).start(any());

        DomainException error = assertThrows(DomainException.class, () -> starts.start(command("rollback-start")));

        assertEquals("START_FAILED", error.code());
        assertEquals(0, count("select count(*) from workflow"));
        assertEquals(0, count("select count(*) from work_item"));
    }

    @Test
    void requiresTrustedTokenAndMatchingCustomerActor() throws Exception {
        String body = "{\"requestId\":\"forbidden-start\",\"customerId\":1,\"taskCode\":\"LOAN_INQUIRY\"}";
        http.perform(post("/api/v1/tools/workflows/start")
                        .header("X-Actor-Id", 1).header("X-Actor-Role", "CUSTOMER")
                        .header("X-Tool-Gateway-Token", "wrong").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/tools/workflows/start")
                        .header("X-Actor-Id", 2).header("X-Actor-Role", "CUSTOMER")
                        .header("X-Tool-Gateway-Token", "workflow-start-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertEquals(0, count("select count(*) from workflow"));
    }

    private WorkflowStartService.StartCommand command(String requestId) {
        return new WorkflowStartService.StartCommand(requestId, 1, TaskCode.LOAN_INQUIRY);
    }

    private org.springframework.http.HttpHeaders headers() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Actor-Id", "1");
        headers.add("X-Actor-Role", "CUSTOMER");
        headers.add("X-Tool-Gateway-Token", "workflow-start-token");
        return headers;
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Number.class).longValue();
    }
}
