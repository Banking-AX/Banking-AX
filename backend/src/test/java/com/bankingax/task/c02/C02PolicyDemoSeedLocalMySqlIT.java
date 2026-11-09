package com.bankingax.task.c02;

import static org.assertj.core.api.Assertions.assertThat;

import com.bankingax.core.policychange.PolicyChange;
import com.bankingax.core.policychange.PolicyChangeType;
import com.bankingax.core.policychange.PolicyTaskProfile;
import com.bankingax.core.notification.NotificationWriter;
import com.bankingax.core.workflow.jpa.WorkflowStartService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The policy demo scenario must leave a Case the bank's power-of-attorney change actually concerns. Truncates: verify DB only. */
@EnabledIfEnvironmentVariable(named = "T2_VERIFY_DB_URL", matches = ".*banking_ax_t2_verify.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = {com.bankingax.BankingAxApplication.class, C02PolicyDemoSeedLocalMySqlIT.Fakes.class})
class C02PolicyDemoSeedLocalMySqlIT {
    @TestConfiguration static class Fakes {
        @Bean @Primary StaffAgentPort fakeAgent() {
            return (requestId, caseId, revision, snapshot) -> new StaffAgentPort.StaffAgentResponse("SUCCEEDED", "READY_FOR_STAFF", List.of(), null);
        }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        String storage = Files.createTempDirectory("c02-policy-seed-").toString();
        registry.add("spring.datasource.url", () -> System.getenv("T2_VERIFY_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("APP_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("APP_DB_PASSWORD"));
        registry.add("app.case-port", () -> "jpa");
        registry.add("app.c02.document-port", () -> "jpa");
        registry.add("app.c02.document-provisioning-enabled", () -> "true");
        registry.add("app.c02.review-persistence-enabled", () -> "true");
        registry.add("app.c02.file-storage-root", () -> storage);
        registry.add("app.jobs.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired C02IntakeService intake;
    @Autowired ApplicationContext context;
    @Autowired C02PolicyProfile profile;

    @BeforeEach void clean() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks=0");
                for (String table : List.of("visit_reservation", "branch", "event_log", "notification", "staff_review", "review_item", "agent_review_run", "document_extraction", "document_file",
                        "required_document", "heir", "clarification", "case_statement", "case_revision", "service_case", "work_item", "workflow", "user"))
                    statement.execute("truncate table `" + table + "`");
                statement.execute("set foreign_key_checks=1");
            }
            return null;
        });
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values(1,'customer_demo','template-hash','CUSTOMER','template')");
        jdbc.update("insert into branch(id,branch_code,name,address,is_active) values(1,'B1','지점','주소',true)");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,branch_id,can_pre_review) values(101,'staff_demo','x','STAFF','직원',1,true)");
    }

    private static PolicyChange poaChange() throws Exception {
        var detail = new ObjectMapper().readTree("""
                {"fictional":true,"before":"b","after":"a","taskExtensions":{"INHERITANCE_DEPOSIT":{"delegationMethods":["DOCUMENT"],"documentTypeCodes":["ABSENT_HEIR_POWER_OF_ATTORNEY"]}}}""");
        Instant now = Instant.now();
        return new PolicyChange("MOCK_BANK_POLICY", "EDU-EVT-C02-POA-0002", "EDU-POLICY-C02-DELEGATION", "EDU-2026.2", 2, "EDU-2026.1", "OPERATION_GUIDE",
                PolicyChangeType.REVIEW_CRITERIA_CHANGE, List.of("INHERITANCE_DEPOSIT"), now, now.plus(14, ChronoUnit.DAYS), "PUBLISHED", "summary",
                new PolicyChange.SourceReference("EDU-DOC-001", "t", "mock://bank/edu/EDU-DOC-001"), detail);
    }

    private C02DemoSeeder seeder() {
        return new C02DemoSeeder(jdbc, context.getBeanProvider(WorkflowStartService.class), intake,
                context.getBeanProvider(C02StaffDecisionPersistence.class), context.getBeanProvider(NotificationWriter.class));
    }

    private void seed(String key) {
        var scenario = C02DemoSeeder.scenarios().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
        seeder().seed(context.getBean(WorkflowStartService.class), scenario);
    }

    @Test void theManualScenarioWaitsForABranchStaffMemberAfterANotifiedResult() {
        seed("visit-manual");
        seed("visit-manual");

        assertThat(jdbc.queryForObject("select count(*) from service_case", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select concat(case_status,'|',result_notified_at is not null) from service_case", String.class)).isEqualTo("REVIEW_PASSED|1");
        assertThat(jdbc.queryForObject("select concat(status,'|',assigned_staff_id is null,'|',starts_at > UTC_TIMESTAMP(6)) from visit_reservation", String.class)).isEqualTo("NEEDS_MANUAL_ASSIGNMENT|1|1");
        assertThat(jdbc.queryForObject("select count(*) from notification where type='VISIT_PENDING_ASSIGNMENT'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select json_unquote(json_extract(payload,'$.exceptionReason')) from event_log where event_type='C02_STAFF_DECIDED'", String.class)).startsWith("시연 데이터");
    }

    @Test void theExpiredScenarioLeavesTheCaseFreeToBookAgain() {
        seed("visit-expired");

        assertThat(jdbc.queryForObject("select status from visit_reservation", String.class)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select active_case_id is null from visit_reservation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification where type='VISIT_EXPIRED'", Integer.class)).isEqualTo(1);
    }

    @Test void theRehearsalScenarioStaysSubmitted() {
        seed("rehearsal");
        assertThat(jdbc.queryForObject("select case_status from service_case", String.class)).isEqualTo("SUBMITTED");
    }

    @Test void thePolicyScenarioLeavesASubmittedPowerOfAttorneyCaseThatTheChangeConcerns() throws Exception {
        var seeder = seeder();
        var scenario = C02DemoSeeder.scenarios().stream().filter(s -> s.key().equals("policy")).findFirst().orElseThrow();

        seeder.seed(context.getBean(WorkflowStartService.class), scenario);
        seeder.seed(context.getBean(WorkflowStartService.class), scenario);

        List<Map<String, Object>> cases = jdbc.queryForList("select id, revision, case_status, submitted_at from service_case");
        assertThat(cases).as("seeding twice leaves one Case").hasSize(1);
        long caseId = ((Number) cases.get(0).get("id")).longValue();
        assertThat(cases.get(0).get("case_status")).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("select password_hash from `user` where login_id='demo_minjun'", String.class)).isEqualTo("template-hash");

        var open = new PolicyTaskProfile.OpenCase(caseId, ((Number) cases.get(0).get("revision")).intValue(), "SUBMITTED", Instant.now(), null);
        assertThat(profile.assess(poaChange(), List.of(open)).get(caseId)).containsExactlyInAnyOrder("DELEGATION_METHOD_MATCH", "REQUIRED_DOCUMENT_MATCH");
    }
}
