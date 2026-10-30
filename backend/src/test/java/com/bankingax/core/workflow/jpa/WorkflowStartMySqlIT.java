package com.bankingax.core.workflow.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.LoanInquiryToolRequest;
import com.bankingax.core.tool.ToolActor;
import com.bankingax.core.tool.ToolGatewayService;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.TaskHandler;
import com.bankingax.task.a02.LoanQueryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
        "app.a02.loan-query-port=jdbc",
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
    @Autowired ObjectMapper json;
    @Autowired ToolGatewayService gateway;
    @SpyBean TaskHandler tasks;
    @SpyBean LoanQueryPort loans;

    @BeforeEach
    void seed() {
        reset(tasks);
        reset(loans);
        jdbc.update("delete from task_result");
        jdbc.update("delete from loan");
        jdbc.update("delete from work_item");
        jdbc.update("delete from workflow");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) "
                + "values(1,'workflow-customer','x','CUSTOMER','customer')");
        jdbc.update("insert into loan(id,customer_id,loan_no,loan_name,principal_amount,balance,interest_rate,"
                + "overdue_days,acceleration_declared,repayment_order) values"
                + "(1,1,'210-111-000001','normal',1000000,900000,3.5,0,false,'INTEREST_FIRST'),"
                + "(2,1,'210-222-000002','overdue',2000000,1800000,4.5,30,true,'PRINCIPAL_FIRST'),"
                + "(3,1,'210-333-000003','excluded',3000000,2700000,5.5,0,false,'PRINCIPAL_FIRST')");
    }

    @AfterEach
    void clearSpy() {
        reset(tasks);
        reset(loans);
    }

    @Test
    void startsA02WorkflowAndReplaysTheSameRequest() throws Exception {
        String body = "{\"requestId\":\"a02-start\",\"customerId\":1,\"taskCode\":\"LOAN_INQUIRY\"}";

        String startResponse = http.perform(post("/api/v1/tools/workflows/start").headers(headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("a02-start"))
                .andExpect(jsonPath("$.taskCode").value("LOAN_INQUIRY"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        long workItemId = json.readTree(startResponse).path("data").path("workItemId").asLong();
        http.perform(post("/api/v1/tools/loan-inquiry").headers(headers()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a02-start\",\"customerId\":1,\"workItemId\":"
                                + workItemId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemId").value(workItemId))
                .andExpect(jsonPath("$.data.loans.length()").value(3))
                .andExpect(jsonPath("$.data.loans[0].loanNoMasked").value("210-***-000001"));
        assertEquals(1, count("select count(*) from task_result where work_item_id=" + workItemId));
        assertEquals(1, count("select count(*) from work_item where id=" + workItemId + " and status='DONE'"));
        assertEquals(1, count("select count(*) from workflow where status='COMPLETED'"));
        clearInvocations(loans);
        http.perform(post("/api/v1/tools/workflows/start").headers(headers()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workflowId").isNumber())
                .andExpect(jsonPath("$.data.workItemId").value(workItemId))
                .andExpect(jsonPath("$.data.status").value("DONE"));
        http.perform(post("/api/v1/tools/loan-inquiry").headers(headers()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"a02-start\",\"customerId\":1,\"workItemId\":"
                                + workItemId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loans.length()").value(3));
        verifyNoInteractions(loans);

        assertEquals(1, count("select count(*) from workflow"));
        assertEquals(0, count("select count(*) from work_item where status='ACTIVE'"));
        assertEquals(1, count("select count(*) from work_item where request_id='a02-start'"));
    }

    @Test
    void rejectsChangedLoanConditionForCompletedRequest() {
        long workItemId = starts.start(command("condition-conflict")).workItemId();
        gateway.loanInquiry(actor(1), request("condition-conflict", workItemId, 1, 1L));

        DomainException error = assertThrows(DomainException.class,
                () -> gateway.loanInquiry(actor(1), request("condition-conflict", workItemId, 1, 2L)));

        assertEquals("DUPLICATE_REQUEST_CONFLICT", error.code());
        assertEquals(1, count("select count(*) from task_result where work_item_id=" + workItemId));
    }

    @Test
    void concurrentLoanInquiryStoresOneResultAndBothRequestsReplayIt() throws Exception {
        long workItemId = starts.start(command("concurrent-inquiry")).workItemId();
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> {
                gate.await();
                return gateway.loanInquiry(actor(1), request("concurrent-inquiry", workItemId, 1, null));
            });
            var second = pool.submit(() -> {
                gate.await();
                return gateway.loanInquiry(actor(1), request("concurrent-inquiry", workItemId, 1, null));
            });
            gate.countDown();

            assertEquals(3, first.get(10, TimeUnit.SECONDS).loans().size());
            assertEquals(3, second.get(10, TimeUnit.SECONDS).loans().size());
            assertEquals(1, count("select count(*) from task_result where work_item_id=" + workItemId));
            assertEquals(1, count("select count(*) from work_item where id=" + workItemId + " and status='DONE'"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lookupFailureLeavesWorkItemActiveAndDoesNotStoreResult() {
        long workItemId = starts.start(command("lookup-failure")).workItemId();
        doThrow(new IllegalStateException("fixture lookup failure"))
                .when(loans).findOwnedLoans(1, null);

        assertThrows(IllegalStateException.class,
                () -> gateway.loanInquiry(actor(1), request("lookup-failure", workItemId, 1, null)));

        assertEquals(0, count("select count(*) from task_result where work_item_id=" + workItemId));
        assertEquals(1, count("select count(*) from work_item where id=" + workItemId + " and status='ACTIVE'"));
    }

    @Test
    void resultStorageFailureRollsBackDoneTransition() {
        long workItemId = starts.start(command("storage-failure")).workItemId();
        jdbc.update("insert into task_result(work_item_id,result_type,detail) values(?,?,cast(? as json))",
                workItemId, "LOAN_INQUIRY", "{\"requestedLoanId\":null,\"loans\":[]}");

        assertThrows(RuntimeException.class,
                () -> gateway.loanInquiry(actor(1), request("storage-failure", workItemId, 1, null)));

        assertEquals(1, count("select count(*) from work_item where id=" + workItemId + " and status='ACTIVE'"));
    }

    @Test
    void completedResultCannotBeReadByAnotherCustomer() {
        long workItemId = starts.start(command("owned-result")).workItemId();
        gateway.loanInquiry(actor(1), request("owned-result", workItemId, 1, null));
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) "
                + "values(2,'other-customer','x','CUSTOMER','other')");

        DomainException error = assertThrows(DomainException.class,
                () -> gateway.loanInquiry(actor(2), request("owned-result", workItemId, 2, null)));

        assertEquals("WORK_ITEM_NOT_AVAILABLE", error.code());
    }

    @Test
    void emptyLoanLookupIsPersistedAndCompleted() {
        long workItemId = starts.start(command("empty-result")).workItemId();

        var response = gateway.loanInquiry(actor(1), request("empty-result", workItemId, 1, 999L));

        assertEquals(List.of(), response.loans());
        assertEquals(1, count("select count(*) from task_result where work_item_id=" + workItemId
                + " and target_loan_id is null"));
        assertEquals(1, count("select count(*) from work_item where id=" + workItemId + " and status='DONE'"));
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

    private ToolActor actor(long customerId) {
        return new ToolActor(customerId, ActorRole.CUSTOMER);
    }

    private LoanInquiryToolRequest request(String requestId, long workItemId, long customerId, Long loanId) {
        return new LoanInquiryToolRequest(requestId, workItemId, customerId, loanId);
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
