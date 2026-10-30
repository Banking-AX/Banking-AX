package com.bankingax.core.workflow.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bankingax.core.api.DomainException;
import com.bankingax.BankingAxApplication;
import com.bankingax.core.caseport.CasePort;
import com.bankingax.core.caseport.CreateCaseCommand;
import com.bankingax.core.workflow.StartTaskCommand;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.TaskHandler;
import com.bankingax.core.workflow.WorkItemStatus;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.case-port=jpa", "app.tool-gateway-token=test-token"})
class JpaTaskHandlerMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_workflow_it").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired TaskHandler tasks;
    @Autowired WorkflowService workflowService;
    @Autowired CasePort cases;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        jdbc.update("delete from case_revision");
        jdbc.update("delete from service_case");
        jdbc.update("delete from loan");
        jdbc.update("delete from work_item");
        jdbc.update("delete from workflow");
        jdbc.update("delete from `user`");
        jdbc.update("delete from branch");
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.update("insert into branch(id,branch_code,name,address) values(1,'IT','IT','test')");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values(1,'customer-1','x','CUSTOMER','one'),(2,'customer-2','x','CUSTOMER','two')");
        jdbc.update("insert into workflow(id,customer_id,status,version,updated_at) values(10,1,'WAITING_CONTINUE',0,'2000-01-01 00:00:00'),(20,2,'WAITING_CONTINUE',0,'2000-01-01 00:00:00'),(30,1,'WAITING_CONTINUE',0,'2000-01-01 00:00:00')");
        jdbc.update("insert into loan(id,customer_id,loan_no,principal_amount,balance) values(501,1,'loan-501',100,100)");
    }

    @Test
    void createsWorkflowThenHoldsResumesAndCompletesPersistently() {
        var workflow = workflowService.create(new WorkflowService.CreateWorkflowCommand(1, null));
        var started = tasks.start(command(1, workflow.workflowId(), TaskCode.LOAN_INQUIRY, workflow.version(), "lifecycle-start"));
        var held = tasks.hold(1, started.workItemId(), started.version(), "LOAN_SELECTED");
        assertEquals(WorkItemStatus.ON_HOLD, held.status());
        assertEquals("LOAN_SELECTED", held.resumeStep());
        assertEquals("WAITING_CONTINUE", workflowStatus(workflow.workflowId()));

        var resumed = tasks.resume(1, held.workItemId(), held.version());
        assertEquals(WorkItemStatus.ACTIVE, resumed.status());
        assertEquals("LOAN_SELECTED", resumed.resumeStep());
        assertEquals("RUNNING", workflowStatus(workflow.workflowId()));

        var completed = tasks.complete(1, resumed.workItemId(), resumed.version());
        assertEquals(WorkItemStatus.DONE, completed.status());
        assertEquals("COMPLETED", workflowStatus(workflow.workflowId()));
        assertEquals("DONE", jdbc.queryForObject("select status from work_item where id=?", String.class, completed.workItemId()));
        assertEquals(3, number("select version from work_item where id=?", completed.workItemId()));
        assertEquals(4, number("select version from workflow where id=?", workflow.workflowId()));
    }

    @Test
    void startsAndPersistsActiveWorkItemAndRunningWorkflow() {
        var created = tasks.start(command(1, 10, TaskCode.DEBT_ADJUSTMENT, 0, "start-1"));
        assertEquals(WorkItemStatus.ACTIVE, created.status());
        assertEquals(0, created.version());
        assertEquals(1, number("select sequence_no from work_item where id=?", created.workItemId()));
        assertEquals("RUNNING", jdbc.queryForObject("select status from workflow where id=10", String.class));
        assertEquals(1, number("select version from workflow where id=10"));
        assertEquals("+00:00", jdbc.queryForObject("select @@session.time_zone", String.class));
        String timing = jdbc.queryForObject(
                "select concat(updated_at, '|', utc_timestamp(6), '|', @@session.time_zone) from workflow where id=10",
                String.class);
        assertTrue(number("select abs(timestampdiff(second, updated_at, utc_timestamp(6))) from workflow where id=10") <= 2,
                timing);
        assertEquals(0, number("select version from work_item where id=?", created.workItemId()));
    }

    @Test
    void replaysSameRequestAndRejectsReuseForAnotherTask() {
        var first = tasks.start(command(1, 10, TaskCode.DEBT_ADJUSTMENT, 0, "same-request"));
        assertEquals(first, tasks.start(command(1, 10, TaskCode.DEBT_ADJUSTMENT, 0, "same-request")));
        assertCode("DUPLICATE_REQUEST_CONFLICT",
                () -> tasks.start(command(1, 10, TaskCode.LOAN_INQUIRY, 1, "same-request")));
    }

    @Test
    void concurrentSameRequestReturnsOnePersistedWorkItem() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> {
                start.await();
                return tasks.start(command(1, 10, TaskCode.DEBT_ADJUSTMENT, 0, "concurrent-same"));
            });
            var second = pool.submit(() -> {
                start.await();
                return tasks.start(command(1, 10, TaskCode.DEBT_ADJUSTMENT, 0, "concurrent-same"));
            });
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, number("select count(*) from work_item where request_id='concurrent-same'"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsForeignWorkflowBeforeRequestIdLookupCanRevealItsWorkItem() {
        tasks.start(command(2, 20, TaskCode.INHERITANCE_DEPOSIT, 0, "customer-2-request"));
        assertCode("FORBIDDEN",
                () -> tasks.start(command(1, 20, TaskCode.INHERITANCE_DEPOSIT, 0, "customer-2-request")));
    }

    @Test
    void rejectsStaleWorkflowVersionAndSecondActiveItem() {
        assertCode("VERSION_CONFLICT", () -> tasks.start(command(1, 10, TaskCode.DEBT_ADJUSTMENT, 1, "stale")));
        tasks.start(command(1, 10, TaskCode.DEBT_ADJUSTMENT, 0, "active-one"));
        assertCode("WORKFLOW_ACTIVE_EXISTS",
                () -> tasks.start(command(1, 10, TaskCode.LOAN_INQUIRY, 1, "active-two")));
        assertEquals(1, number("select count(*) from work_item where workflow_id=10 and status='ACTIVE'"));
    }

    @Test
    void assignsNextSequenceAfterExistingNonActiveItems() {
        jdbc.update("insert into work_item(workflow_id,task_code,sequence_no,status,request_id) values(30,'LOAN_INQUIRY',1,'DONE','done-1'),(30,'TRANSACTION_SEARCH',2,'ON_HOLD','held-2')");
        var created = tasks.start(command(1, 30, TaskCode.DEBT_ADJUSTMENT, 0, "next-sequence"));
        assertEquals(3, number("select sequence_no from work_item where id=?", created.workItemId()));
    }

    @Test
    void rejectsForeignAndStaleTransitionsAndPreventsResumeWithAnotherActiveItem() {
        var heldSource = tasks.start(command(1, 10, TaskCode.DEBT_ADJUSTMENT, 0, "transition-source"));
        assertCode("FORBIDDEN", () -> tasks.hold(2, heldSource.workItemId(), heldSource.version(), "PRIVATE_STEP"));
        assertCode("VERSION_CONFLICT", () -> tasks.hold(1, heldSource.workItemId(), 99, "STALE"));
        var held = tasks.hold(1, heldSource.workItemId(), heldSource.version(), "SAVED_STEP");
        jdbc.update("insert into work_item(workflow_id,task_code,sequence_no,status,request_id) values(10,'LOAN_INQUIRY',2,'ACTIVE','other-active')");
        assertCode("WORKFLOW_ACTIVE_EXISTS", () -> tasks.resume(1, held.workItemId(), held.version()));
        assertEquals("ON_HOLD", jdbc.queryForObject("select status from work_item where id=?", String.class, held.workItemId()));
    }

    @Test
    void createsServiceCaseWithThePersistedWorkItemForeignKey() {
        var work = tasks.start(command(1, 10, TaskCode.DEBT_ADJUSTMENT, 0, "case-work-item"));
        var serviceCase = cases.create(new CreateCaseCommand(
                1, work.workItemId(), TaskCode.DEBT_ADJUSTMENT, 501L, Map.of("reason", "fixture"), null, false));
        assertEquals(work.workItemId(), serviceCase.workItemId());
        assertEquals(1, number("select count(*) from service_case where work_item_id=?", work.workItemId()));
    }

    @Test
    void storedStateIsReadableFromANewSpringContext() {
        var work = tasks.start(command(1, 10, TaskCode.LOAN_INQUIRY, 0, "restart-state"));
        var held = tasks.hold(1, work.workItemId(), work.version(), "RESTORE_ME");
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(BankingAxApplication.class)
                .web(WebApplicationType.NONE)
                .run("--app.case-port=jpa", "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--app.tool-gateway-token=test-token")) {
            JdbcTemplate afterRestart = restarted.getBean(JdbcTemplate.class);
            assertEquals("ON_HOLD", afterRestart.queryForObject(
                    "select status from work_item where id=?", String.class, held.workItemId()));
            assertEquals("RESTORE_ME", afterRestart.queryForObject(
                    "select resume_step from work_item where id=?", String.class, held.workItemId()));
        }
    }

    private StartTaskCommand command(long actor, long workflow, TaskCode task, int version, String requestId) {
        return new StartTaskCommand(actor, workflow, task, version, requestId);
    }

    private long number(String sql, Object... args) {
        return jdbc.queryForObject(sql, Number.class, args).longValue();
    }

    private String workflowStatus(long workflowId) {
        return jdbc.queryForObject("select status from workflow where id=?", String.class, workflowId);
    }

    private void assertCode(String code, Runnable action) {
        var error = assertThrows(DomainException.class, action::run);
        assertEquals(code, error.code());
    }
}
