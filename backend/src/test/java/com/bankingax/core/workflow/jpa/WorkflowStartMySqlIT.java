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
import com.bankingax.core.workflow.WorkItemStatus;
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
        jdbc.update("delete from chat_message");
        jdbc.update("delete from chat_room");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) "
                + "values(1,'workflow-customer','x','CUSTOMER','customer')");
        jdbc.update("insert into chat_room(id,customer_id) values(77,1)");
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
        String body = "{\"requestId\":\"a02-start\",\"customerId\":1,\"chatRoomId\":77,"
                + "\"taskCode\":\"LOAN_INQUIRY\"}";

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
                .andExpect(jsonPath("$.data.loans[0].loanNoMasked").value("210-***-000001"))
                .andExpect(jsonPath("$.data.workItemStatus").value("DONE"))
                .andExpect(jsonPath("$.data.workflowStatus").value("COMPLETED"));
        assertEquals(1, count("select count(*) from task_result where work_item_id=" + workItemId));
        assertEquals(1, count("select count(*) from work_item where id=" + workItemId + " and status='DONE'"));
        assertEquals(1, count("select count(*) from workflow where status='COMPLETED'"));
        assertEquals(1, count("select count(*) from workflow where chat_room_id=77"));
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
    void newA02RequestsRunIndependentlyWhileAnEarlierRequestRemainsActive() {
        jdbc.update("insert into chat_room(id,customer_id) values(78,1)");
        var earlier = starts.start(new WorkflowStartService.StartCommand(
                "a02-earlier-active", 1, 77L, TaskCode.LOAN_INQUIRY));

        var sameRoom = starts.start(new WorkflowStartService.StartCommand(
                "a02-same-room-new", 1, 77L, TaskCode.LOAN_INQUIRY));
        var otherRoom = starts.start(new WorkflowStartService.StartCommand(
                "a02-other-room-new", 1, 78L, TaskCode.LOAN_INQUIRY));

        org.junit.jupiter.api.Assertions.assertNotEquals(earlier.workItemId(), sameRoom.workItemId());
        org.junit.jupiter.api.Assertions.assertNotEquals(earlier.workItemId(), otherRoom.workItemId());
        org.junit.jupiter.api.Assertions.assertNotEquals(sameRoom.workItemId(), otherRoom.workItemId());

        gateway.loanInquiry(actor(1), request("a02-same-room-new", sameRoom.workItemId(), 1, null));
        gateway.loanInquiry(actor(1), request("a02-other-room-new", otherRoom.workItemId(), 1, null));

        assertEquals(1, count("select count(*) from work_item where id=" + earlier.workItemId()
                + " and status='ACTIVE'"));
        assertEquals(2, count("select count(*) from work_item where id in (" + sameRoom.workItemId() + ","
                + otherRoom.workItemId() + ") and status='DONE'"));
        assertEquals(2, count("select count(*) from task_result where work_item_id in ("
                + sameRoom.workItemId() + "," + otherRoom.workItemId() + ")"));
    }

    @Test
    void resumesExistingActiveItemForTheSameCustomerAndTask() {
        var first = starts.start(new WorkflowStartService.StartCommand(
                "b02-first", 1, 77L, TaskCode.HOUSING_SAVINGS_CLOSE));
        var resumed = starts.start(new WorkflowStartService.StartCommand(
                "b02-second", 1, 77L, TaskCode.HOUSING_SAVINGS_CLOSE));

        assertEquals(first, resumed);
        assertEquals(1, count("select count(*) from workflow"));
        assertEquals(1, count("select count(*) from work_item where task_code='HOUSING_SAVINGS_CLOSE' and status='ACTIVE'"));
        assertEquals(0, count("select count(*) from task_result"));
    }

    @Test
    void preparesNewB01AndReplaysTheSameRequest() throws Exception {
        String body = "{\"requestId\":\"b01-prepare-1\",\"customerId\":1,\"chatRoomId\":77,"
                + "\"mode\":\"DISCOVER\"}";

        String first = http.perform(post("/api/v1/tools/workflows/b01/prepare").headers(headers())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskCode").value("REPAYMENT_ORDER_CHANGE"))
                .andExpect(jsonPath("$.data.outcome").value("CREATED"))
                .andExpect(jsonPath("$.data.workItem.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        long workItemId = json.readTree(first).path("data").path("workItem").path("workItemId").asLong();

        http.perform(post("/api/v1/tools/workflows/b01/prepare").headers(headers())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("REQUEST_REPLAYED"))
                .andExpect(jsonPath("$.data.workItem.workItemId").value(workItemId));
        assertEquals(1, count("select count(*) from work_item where task_code='REPAYMENT_ORDER_CHANGE'"));
    }

    @Test
    void reusesUnfinishedB01InTheSameChat() {
        var first = prepareB01("b01-same-first", 1, 77, WorkflowStartService.B01PrepareMode.DISCOVER, null);
        var reused = prepareB01("b01-same-second", 1, 77, WorkflowStartService.B01PrepareMode.DISCOVER, null);

        assertEquals(WorkflowStartService.B01PrepareOutcome.SAME_CHAT_REUSED, reused.outcome());
        assertEquals(first.workItem().workItemId(), reused.workItem().workItemId());
        assertEquals(1, count("select count(*) from workflow"));
    }

    @Test
    void requiresConfirmationForUnfinishedB01InAnotherChat() {
        jdbc.update("insert into chat_room(id,customer_id) values(78,1)");
        var existing = prepareB01("b01-room-77", 1, 77, WorkflowStartService.B01PrepareMode.DISCOVER, null);

        var found = prepareB01("b01-room-78", 1, 78, WorkflowStartService.B01PrepareMode.DISCOVER, null);

        assertEquals(WorkflowStartService.B01PrepareOutcome.RECONNECT_CONFIRMATION_REQUIRED, found.outcome());
        assertEquals(existing.workItem().workItemId(), found.workItem().workItemId());
        assertEquals(1, count("select count(*) from workflow"));
    }

    @Test
    void optionallyReturnsAllReconnectCandidatesInStableOrder() throws Exception {
        jdbc.update("insert into chat_room(id,customer_id) values(78,1),(79,1)");
        var held = prepareB01("b01-candidate-held", 1, 77,
                WorkflowStartService.B01PrepareMode.DISCOVER, null);
        jdbc.update("update work_item set status='ON_HOLD' where id=?", held.workItem().workItemId());
        var active = prepareB01("b01-candidate-active", 1, 78,
                WorkflowStartService.B01PrepareMode.START_NEW, null);

        http.perform(post("/api/v1/tools/workflows/b01/prepare").headers(headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"b01-candidate-discover\",\"customerId\":1,"
                                + "\"chatRoomId\":79,\"mode\":\"DISCOVER\","
                                + "\"includeCandidates\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("RECONNECT_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.data.candidates.length()").value(2))
                .andExpect(jsonPath("$.data.candidates[0].workItemId").value(active.workItem().workItemId()))
                .andExpect(jsonPath("$.data.candidates[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.candidates[0].originalChatRoomId").value(78))
                .andExpect(jsonPath("$.data.candidates[1].workItemId").value(held.workItem().workItemId()))
                .andExpect(jsonPath("$.data.candidates[1].status").value("ON_HOLD"));

        http.perform(post("/api/v1/tools/workflows/b01/prepare").headers(headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"b01-candidate-legacy\",\"customerId\":1,"
                                + "\"chatRoomId\":79,\"mode\":\"DISCOVER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates").doesNotExist());
    }

    @Test
    void explicitlyContinuesOwnedB01WithoutChangingItsChatRoomOrStatus() {
        jdbc.update("insert into chat_room(id,customer_id) values(78,1)");
        var existing = prepareB01("b01-continue-source", 1, 77,
                WorkflowStartService.B01PrepareMode.DISCOVER, null);
        jdbc.update("update work_item set status='ON_HOLD', resume_step='DOCUMENT_UPLOAD' where id=?",
                existing.workItem().workItemId());

        var continued = prepareB01("b01-continue-choice", 1, 78,
                WorkflowStartService.B01PrepareMode.CONTINUE_EXISTING, existing.workItem().workItemId());

        assertEquals(WorkflowStartService.B01PrepareOutcome.EXISTING_RECONNECTED, continued.outcome());
        assertEquals(existing.workItem().workItemId(), continued.workItem().workItemId());
        assertEquals(WorkItemStatus.ON_HOLD, continued.workItem().status());
        assertEquals(1, count("select count(*) from workflow where id=" + existing.workItem().workflowId()
                + " and chat_room_id=77"));
    }

    @Test
    void rejectsStartingNewB01WhileAnotherChatHasAnActiveItem() {
        jdbc.update("insert into chat_room(id,customer_id) values(78,1)");
        var existing = prepareB01("b01-active-source", 1, 77,
                WorkflowStartService.B01PrepareMode.DISCOVER, null);

        var conflict = prepareB01("b01-start-new", 1, 78,
                WorkflowStartService.B01PrepareMode.START_NEW, null);

        assertEquals(WorkflowStartService.B01PrepareOutcome.ACTIVE_WORK_ITEM_CONFLICT, conflict.outcome());
        assertEquals(existing.workItem().workItemId(), conflict.workItem().workItemId());
        assertEquals(1, count("select count(*) from work_item where task_code='REPAYMENT_ORDER_CHANGE'"));
    }

    @Test
    void startsNewB01WhenAnotherChatOnlyHasAnOnHoldItem() {
        jdbc.update("insert into chat_room(id,customer_id) values(78,1)");
        var existing = prepareB01("b01-hold-source", 1, 77,
                WorkflowStartService.B01PrepareMode.DISCOVER, null);
        jdbc.update("update work_item set status='ON_HOLD' where id=?", existing.workItem().workItemId());

        var created = prepareB01("b01-new-after-hold", 1, 78,
                WorkflowStartService.B01PrepareMode.START_NEW, null);

        assertEquals(WorkflowStartService.B01PrepareOutcome.CREATED, created.outcome());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                existing.workItem().workItemId(), created.workItem().workItemId());
        assertEquals(1, count("select count(*) from work_item where status='ON_HOLD'"));
        assertEquals(1, count("select count(*) from work_item where status='ACTIVE'"));
    }

    @Test
    void neverContinuesAnotherCustomersB01() {
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) "
                + "values(2,'other-b01','x','CUSTOMER','other')");
        jdbc.update("insert into chat_room(id,customer_id) values(78,2)");
        var other = prepareB01("b01-other-owned", 2, 78,
                WorkflowStartService.B01PrepareMode.DISCOVER, null);

        DomainException error = assertThrows(DomainException.class, () -> prepareB01(
                "b01-foreign-continue", 1, 77, WorkflowStartService.B01PrepareMode.CONTINUE_EXISTING,
                other.workItem().workItemId()));

        assertEquals("WORK_ITEM_NOT_AVAILABLE", error.code());
    }

    @Test
    void concurrentB01DiscoveryCreatesOneWorkItem() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> {
                gate.await();
                return prepareB01("b01-concurrent-first", 1, 77,
                        WorkflowStartService.B01PrepareMode.DISCOVER, null);
            });
            var second = pool.submit(() -> {
                gate.await();
                return prepareB01("b01-concurrent-second", 1, 77,
                        WorkflowStartService.B01PrepareMode.DISCOVER, null);
            });
            gate.countDown();

            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            assertEquals(firstResult.workItem().workItemId(), secondResult.workItem().workItemId());
            assertEquals(1, count("select count(*) from work_item where task_code='REPAYMENT_ORDER_CHANGE'"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void doesNotReuseDoneItem() {
        var done = starts.start(new WorkflowStartService.StartCommand(
                "a01-done", 1, 77L, TaskCode.TRANSACTION_SEARCH));
        jdbc.update("update work_item set status='DONE' where id=?", done.workItemId());
        jdbc.update("update workflow set status='COMPLETED' where id=?", done.workflowId());

        var next = starts.start(new WorkflowStartService.StartCommand(
                "a01-next", 1, 77L, TaskCode.TRANSACTION_SEARCH));

        org.junit.jupiter.api.Assertions.assertNotEquals(done.workItemId(), next.workItemId());
        assertEquals(1, count("select count(*) from work_item where task_code='TRANSACTION_SEARCH' and status='DONE'"));
        assertEquals(1, count("select count(*) from work_item where task_code='TRANSACTION_SEARCH' and status='ACTIVE'"));
    }

    @Test
    void neverResumesAnotherCustomersActiveItem() {
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values(2,'other','x','CUSTOMER','other')");
        jdbc.update("insert into chat_room(id,customer_id) values(78,2)");
        var other = starts.start(new WorkflowStartService.StartCommand(
                "other-b02", 2, 78L, TaskCode.HOUSING_SAVINGS_CLOSE));

        var own = starts.start(new WorkflowStartService.StartCommand(
                "own-b02", 1, 77L, TaskCode.HOUSING_SAVINGS_CLOSE));

        org.junit.jupiter.api.Assertions.assertNotEquals(other.workItemId(), own.workItemId());
        assertEquals(2, count("select count(*) from work_item where task_code='HOUSING_SAVINGS_CLOSE' and status='ACTIVE'"));
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
    void rejectsReusingAnOperationRequestInAnotherChatRoom() throws Exception {
        jdbc.update("insert into chat_room(id,customer_id) values(78,1)");
        starts.start(new WorkflowStartService.StartCommand(
                "room-bound-request", 1, 77L, TaskCode.LOAN_INQUIRY));

        http.perform(post("/api/v1/tools/workflows/start").headers(headers())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"room-bound-request\",\"customerId\":1,"
                                + "\"chatRoomId\":78,\"taskCode\":\"LOAN_INQUIRY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_REQUEST_CONFLICT"));

        assertEquals(1, count("select count(*) from workflow where chat_room_id=77"));
        assertEquals(0, count("select count(*) from workflow where chat_room_id=78"));
        assertEquals(1, count("select count(*) from work_item where request_id='room-bound-request'"));
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

    private WorkflowStartService.B01PrepareResult prepareB01(
            String requestId, long customerId, long chatRoomId,
            WorkflowStartService.B01PrepareMode mode, Long workItemId) {
        return starts.prepareB01(new WorkflowStartService.B01PrepareCommand(
                requestId, customerId, chatRoomId, mode, workItemId));
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
