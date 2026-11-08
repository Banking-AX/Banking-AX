package com.bankingax.task.b01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class B01LoanListMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("banking_ax_b01_loan_list_it")
            .withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("app.case-port", () -> "jpa");
        properties.add("app.auth.enabled", () -> "true");
        properties.add("app.auth.jwt-secret",
                () -> "b01-loan-list-test-secret-material-that-is-longer-than-sixty-four-characters");
        properties.add("app.tool-gateway-token", () -> "b01-loan-tool-token");
    }

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired B01ProgressService progress;

    @BeforeEach
    void seed() {
        jdbc.update("delete from event_log");
        jdbc.update("delete from consent_record");
        jdbc.update("delete from loan");
        jdbc.update("delete from work_item");
        jdbc.update("delete from workflow");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values "
                + "(1,'b01-list-one','x','CUSTOMER','one'),"
                + "(2,'b01-list-two','x','CUSTOMER','two'),"
                + "(3,'b01-list-empty','x','CUSTOMER','empty')");
        jdbc.update("insert into workflow(id,customer_id,status) values "
                + "(10,1,'RUNNING'),(20,2,'RUNNING'),(30,1,'RUNNING'),(40,1,'RUNNING'),"
                + "(50,3,'RUNNING'),(60,1,'COMPLETED')");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values "
                + "(100,10,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','b01-list-100'),"
                + "(200,20,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','b01-list-200'),"
                + "(300,30,'LOAN_INQUIRY',1,'ACTIVE','b01-list-300'),"
                + "(400,40,'REPAYMENT_ORDER_CHANGE',1,'ON_HOLD','b01-list-400'),"
                + "(500,50,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','b01-list-500'),"
                + "(600,60,'REPAYMENT_ORDER_CHANGE',1,'DONE','b01-list-600')");
        jdbc.update("""
                insert into loan(id,customer_id,loan_no,loan_name,principal_amount,balance,overdue_days,
                  acceleration_declared,acceleration_date,repayment_order)
                values (501,1,'210-123-557731','synthetic one',30000000,26500000,62,
                          true,'2026-08-31','INTEREST_FIRST'),
                       (502,1,'220-456-114820','synthetic two',10000000,9000000,0,
                          false,null,'PRINCIPAL_FIRST'),
                       (601,2,'999-777-123456','foreign synthetic',1000,900,1,
                          true,'2026-08-01','INTEREST_FIRST')
                """);
    }

    @Test
    void returnsOnlyAuthenticatedCustomersLoansInIdOrderWithMaskedNumbers() throws Exception {
        String response = http.perform(get("/api/v1/customer/b01/work-items/{id}/loans", 100)
                        .with(customer(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskCode").value("REPAYMENT_ORDER_CHANGE"))
                .andExpect(jsonPath("$.workItemId").value(100))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].loanId").value(501))
                .andExpect(jsonPath("$.data[0].maskedAccountNumber").value("210-***-557731"))
                .andExpect(jsonPath("$.data[0].loanName").value("synthetic one"))
                .andExpect(jsonPath("$.data[0].balance").value(26500000))
                .andExpect(jsonPath("$.data[0].overdueDays").value(62))
                .andExpect(jsonPath("$.data[0].accelerationDeclared").value(true))
                .andExpect(jsonPath("$.data[0].accelerationDate").value("2026-08-31"))
                .andExpect(jsonPath("$.data[0].currentRepaymentOrder").value("INTEREST_FIRST"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("210-123-557731")
                .doesNotContain("999-777-123456")
                .doesNotContain("foreign synthetic");
    }

    @Test
    void rejectsForeignWrongTaskMissingAndInactiveWorkItemsWithoutRevealingOwnership() throws Exception {
        for (long workItemId : new long[]{200, 300, 400, 999}) {
            http.perform(get("/api/v1/customer/b01/work-items/{id}/loans", workItemId).with(customer(1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("WORK_ITEM_NOT_AVAILABLE"));
        }
    }

    @Test
    void returnsEmptyListWhenOwnedCustomerHasNoLoans() throws Exception {
        http.perform(get("/api/v1/customer/b01/work-items/{id}/loans", 500).with(customer(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void requiresCustomerJwt() throws Exception {
        http.perform(get("/api/v1/customer/b01/work-items/{id}/loans", 100))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/customer/b01/work-items/{id}/loans", 100)
                        .with(jwt().jwt(token -> token.subject("101").claim("role", "STAFF"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalToolReturnsMultipleMaskedLoanCandidatesInIdOrder() throws Exception {
        String response = http.perform(post("/api/v1/tools/b01/loans")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"tool-loans-1\",\"workItemId\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("tool-loans-1"))
                .andExpect(jsonPath("$.taskCode").value("REPAYMENT_ORDER_CHANGE"))
                .andExpect(jsonPath("$.workItemId").value(100))
                .andExpect(jsonPath("$.data.loans.length()").value(2))
                .andExpect(jsonPath("$.data.loans[0].loanId").value(501))
                .andExpect(jsonPath("$.data.loans[0].loanName").value("synthetic one"))
                .andExpect(jsonPath("$.data.loans[0].loanNoMasked").value("210-***-557731"))
                .andExpect(jsonPath("$.data.loans[0].repaymentOrder").value("INTEREST_FIRST"))
                .andExpect(jsonPath("$.data.loans[1].loanId").value(502))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("210-123-557731")
                .doesNotContain("220-456-114820")
                .doesNotContain("999-777-123456")
                .doesNotContain("balance");
    }

    @Test
    void internalToolReturnsOneOrZeroLoansWithTheSameShape() throws Exception {
        http.perform(post("/api/v1/tools/b01/loans")
                        .headers(toolHeaders(2, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"tool-loans-one\",\"workItemId\":200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loans.length()").value(1))
                .andExpect(jsonPath("$.data.loans[0].loanId").value(601));

        http.perform(post("/api/v1/tools/b01/loans")
                        .headers(toolHeaders(3, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"tool-loans-empty\",\"workItemId\":500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loans.length()").value(0));
    }

    @Test
    void internalToolRejectsForeignWrongTaskMissingAndEndedWorkItems() throws Exception {
        for (long workItemId : new long[]{200, 300, 600, 999}) {
            http.perform(post("/api/v1/tools/b01/loans")
                            .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"tool-invalid-" + workItemId
                                    + "\",\"workItemId\":" + workItemId + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("WORK_ITEM_NOT_AVAILABLE"));
        }
    }

    @Test
    void internalToolRequiresExplicitResumeForOnHoldWorkItemWithoutChangingIt() throws Exception {
        http.perform(post("/api/v1/tools/b01/loans")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"tool-on-hold\",\"workItemId\":400}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("WORK_ITEM_RESUME_REQUIRED"));

        assertThat(jdbc.queryForObject("select status from work_item where id=400", String.class))
                .isEqualTo("ON_HOLD");
    }

    @Test
    void internalToolRequiresTrustedCustomerGatewayAuthentication() throws Exception {
        String body = "{\"requestId\":\"tool-auth\",\"workItemId\":100}";
        http.perform(post("/api/v1/tools/b01/loans")
                        .header("X-Actor-Id", 1).header("X-Actor-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/tools/b01/loans")
                        .headers(toolHeaders(1, "CUSTOMER", "wrong-token"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/tools/b01/loans")
                        .headers(toolHeaders(1, "STAFF", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void internalToolSavesSelectionAndCustomerContextRestoresIt() throws Exception {
        http.perform(post("/api/v1/tools/b01/selection")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selection("selection-1", 100, 501, "PRINCIPAL_FIRST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("selection-1"))
                .andExpect(jsonPath("$.taskCode").value("REPAYMENT_ORDER_CHANGE"))
                .andExpect(jsonPath("$.workItemId").value(100))
                .andExpect(jsonPath("$.data.selectedLoanId").value(501))
                .andExpect(jsonPath("$.data.requestedRepaymentOrder").value("PRINCIPAL_FIRST"))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andExpect(jsonPath("$.data.workItemVersion").value(1));

        http.perform(get("/api/v1/customer/b01/work-items/{id}/context", 100).with(customer(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedLoanId").value(501))
                .andExpect(jsonPath("$.data.requestedRepaymentOrder").value("PRINCIPAL_FIRST"))
                .andExpect(jsonPath("$.data.currentStep").value("APPLICATION_UPLOAD"));
    }

    @Test
    void eligibilityToolChecksTheSavedSelectionWithoutChangingDatabaseState() throws Exception {
        progress.saveSelectionForTool(1, 100,
                new B01ProgressService.SelectionCommand("eligibility-selection", 501, "PRINCIPAL_FIRST"));
        String loanBefore = jdbc.queryForObject("select json_object('order',repayment_order,'version',version) "
                + "from loan where id=501", String.class);
        String draftBefore = jdbc.queryForObject("select draft_data from work_item where id=100", String.class);
        int versionBefore = jdbc.queryForObject("select version from work_item where id=100", Integer.class);

        http.perform(post("/api/v1/tools/b01/eligibility")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"eligibility-1\",\"workItemId\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("eligibility-1"))
                .andExpect(jsonPath("$.taskCode").value("REPAYMENT_ORDER_CHANGE"))
                .andExpect(jsonPath("$.workItemId").value(100))
                .andExpect(jsonPath("$.data.selectedLoanId").value(501))
                .andExpect(jsonPath("$.data.requestedRepaymentOrder").value("PRINCIPAL_FIRST"))
                .andExpect(jsonPath("$.data.eligibilityStatus").value("ELIGIBLE"))
                .andExpect(jsonPath("$.data.reasonCode").value("ELIGIBLE"))
                .andExpect(jsonPath("$.data.reasonMessage").isNotEmpty())
                .andExpect(jsonPath("$.data.balance").doesNotExist())
                .andExpect(jsonPath("$.data.loanNo").doesNotExist());

        assertThat(jdbc.queryForObject("select json_object('order',repayment_order,'version',version) "
                + "from loan where id=501", String.class)).isEqualTo(loanBefore);
        assertThat(jdbc.queryForObject("select draft_data from work_item where id=100", String.class))
                .isEqualTo(draftBefore);
        assertThat(jdbc.queryForObject("select version from work_item where id=100", Integer.class))
                .isEqualTo(versionBefore);
    }

    @Test
    void eligibilityToolReturnsEachExistingLoanRuleResult() throws Exception {
        assertRule("rule-not-accelerated", "update loan set acceleration_declared=false where id=501",
                "PRINCIPAL_FIRST", "NOT_ACCELERATED");
        assertRule("rule-outsourced", "update loan set outsourced=true where id=501",
                "PRINCIPAL_FIRST", "OUTSOURCED_LOAN");
        assertRule("rule-agreement", "update loan set agreement_loan=true where id=501",
                "PRINCIPAL_FIRST", "AGREEMENT_LOAN");
        assertRule("rule-already-applied", "update loan set repayment_order='INTEREST_FIRST' where id=501",
                "INTEREST_FIRST", "ALREADY_APPLIED");
    }

    @Test
    void eligibilityToolRequiresACompleteCurrentSelection() throws Exception {
        http.perform(post("/api/v1/tools/b01/eligibility")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"missing-all\",\"workItemId\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("B01_SELECTION_REQUIRED"));

        jdbc.update("update work_item set draft_data=json_object('selectedLoanId',501) where id=100");
        http.perform(post("/api/v1/tools/b01/eligibility")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"missing-order\",\"workItemId\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("B01_SELECTION_REQUIRED"));
    }

    @Test
    void eligibilityToolRejectsUnavailableWorkItemsLoansAndUntrustedCallers() throws Exception {
        jdbc.update("update work_item set draft_data=json_object('selectedLoanId',501,"
                + "'requestedRepaymentOrder','PRINCIPAL_FIRST') where id in (200,300,400,600)");
        http.perform(post("/api/v1/tools/b01/eligibility")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"held\",\"workItemId\":400}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("WORK_ITEM_RESUME_REQUIRED"));
        for (long workItemId : new long[]{200, 300, 600, 999}) {
            http.perform(post("/api/v1/tools/b01/eligibility")
                            .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"invalid-" + workItemId
                                    + "\",\"workItemId\":" + workItemId + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("WORK_ITEM_NOT_AVAILABLE"));
        }

        jdbc.update("update work_item set draft_data=json_object('selectedLoanId',601,"
                + "'requestedRepaymentOrder','PRINCIPAL_FIRST') where id=100");
        http.perform(post("/api/v1/tools/b01/eligibility")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"foreign-loan\",\"workItemId\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LOAN_NOT_AVAILABLE"));

        String body = "{\"requestId\":\"auth\",\"workItemId\":100}";
        http.perform(post("/api/v1/tools/b01/eligibility")
                        .header("X-Actor-Id", 1).header("X-Actor-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/tools/b01/eligibility")
                        .headers(toolHeaders(1, "STAFF", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void selectionRetriesAndEquivalentNewRequestsDoNotIncrementVersion() throws Exception {
        var first = progress.saveSelectionForTool(1, 100,
                new B01ProgressService.SelectionCommand("selection-retry", 501, "PRINCIPAL_FIRST"));
        var replay = progress.saveSelectionForTool(1, 100,
                new B01ProgressService.SelectionCommand("selection-retry", 501, "PRINCIPAL_FIRST"));
        var equivalent = progress.saveSelectionForTool(1, 100,
                new B01ProgressService.SelectionCommand("selection-equivalent", 501, "PRINCIPAL_FIRST"));

        assertThat(first.changed()).isTrue();
        assertThat(replay.changed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(equivalent.changed()).isFalse();
        assertThat(equivalent.replayed()).isFalse();
        assertThat(first.workItemVersion()).isEqualTo(1);
        assertThat(replay.workItemVersion()).isEqualTo(1);
        assertThat(equivalent.workItemVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from work_item where id=100", Integer.class)).isEqualTo(1);

        http.perform(post("/api/v1/tools/b01/selection")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selection("selection-retry", 100, 502, "INTEREST_FIRST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_REQUEST_CONFLICT"));
    }

    @Test
    void historicalReplayDoesNotRollBackTheCurrentSelectionOrItsProgress() {
        var a = new B01ProgressService.SelectionCommand("history-a", 501, "PRINCIPAL_FIRST");
        var b = new B01ProgressService.SelectionCommand("history-b", 502, "INTEREST_FIRST");
        progress.saveSelectionForTool(1, 100, a);
        progress.saveSelectionForTool(1, 100, b);
        jdbc.update("""
                update work_item set draft_data=json_set(draft_data,
                  '$.documentFileId',77,'$.lastEligibility',json_object('loanId',502),
                  '$.consentAgreed',true,'$.mockSignatureCompleted',true,
                  '$.smsVerified',true,'$.customAgentNote','keep-current') where id=100
                """);
        int beforeVersion = jdbc.queryForObject("select version from work_item where id=100", Integer.class);

        var replay = progress.saveSelectionForTool(1, 100, a);

        assertThat(replay.changed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.selectedLoanId()).isEqualTo(502L);
        assertThat(replay.requestedRepaymentOrder()).isEqualTo("INTEREST_FIRST");
        assertThat(replay.workItemVersion()).isEqualTo(beforeVersion);
        assertThat(jdbc.queryForObject("select version from work_item where id=100", Integer.class))
                .isEqualTo(beforeVersion);
        String draft = jdbc.queryForObject("select draft_data from work_item where id=100", String.class);
        assertThat(draft).contains("documentFileId", "lastEligibility", "consentAgreed",
                "mockSignatureCompleted", "smsVerified", "keep-current");

        var explicitNewChange = progress.saveSelectionForTool(1, 100,
                new B01ProgressService.SelectionCommand("history-c", 501, "PRINCIPAL_FIRST"));
        assertThat(explicitNewChange.changed()).isTrue();
        assertThat(explicitNewChange.replayed()).isFalse();
        assertThat(explicitNewChange.selectedLoanId()).isEqualTo(501L);
        assertThat(jdbc.queryForObject("select count(*) from event_log where event_type='B01_SELECTION_SAVED'",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void changedSelectionInvalidatesDependentStateButPreservesUnrelatedDraftData() throws Exception {
        jdbc.update("""
                update work_item set draft_data=json_object(
                  'selectedLoanId',501,'requestedRepaymentOrder','PRINCIPAL_FIRST',
                  'documentFileId',77,'documentVersion',2,'lastEligibility',json_object('loanId',501),
                  'applicationFingerprint','fingerprint','documentChecksum','checksum',
                  'consentRecordId',88,'consentAgreed',true,'mockSignatureCompleted',true,
                  'smsChallengeId','challenge','smsVerified',true,'customAgentNote','keep-me')
                 where id=100
                """);

        http.perform(post("/api/v1/tools/b01/selection")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selection("selection-change", 100, 502, "INTEREST_FIRST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true));

        String draft = jdbc.queryForObject("select draft_data from work_item where id=100", String.class);
        assertThat(jdbc.queryForObject("select json_unquote(json_extract(draft_data,'$.selectedLoanId')) "
                + "from work_item where id=100", Long.class)).isEqualTo(502L);
        assertThat(jdbc.queryForObject("select json_unquote(json_extract(draft_data,'$.requestedRepaymentOrder')) "
                + "from work_item where id=100", String.class)).isEqualTo("INTEREST_FIRST");
        assertThat(jdbc.queryForObject("select json_unquote(json_extract(draft_data,'$.customAgentNote')) "
                + "from work_item where id=100", String.class)).isEqualTo("keep-me");
        assertThat(draft)
                .doesNotContain("documentFileId", "lastEligibility", "applicationFingerprint",
                        "consentAgreed", "mockSignatureCompleted", "smsChallengeId", "smsVerified");
    }

    @Test
    void selectionToolRejectsInvalidOwnershipStateOrderAndAuthentication() throws Exception {
        for (long workItemId : new long[]{200, 300, 600, 999}) {
            http.perform(post("/api/v1/tools/b01/selection")
                            .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(selection("selection-invalid-" + workItemId, workItemId, 501,
                                    "PRINCIPAL_FIRST")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("WORK_ITEM_NOT_AVAILABLE"));
        }
        http.perform(post("/api/v1/tools/b01/selection")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selection("selection-foreign-loan", 100, 601, "PRINCIPAL_FIRST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LOAN_NOT_AVAILABLE"));
        http.perform(post("/api/v1/tools/b01/selection")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selection("selection-order", 100, 501, "UNKNOWN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REPAYMENT_ORDER"));
        http.perform(post("/api/v1/tools/b01/selection")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selection("selection-held", 400, 501, "PRINCIPAL_FIRST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("WORK_ITEM_RESUME_REQUIRED"));
        http.perform(post("/api/v1/tools/b01/selection")
                        .headers(toolHeaders(1, "STAFF", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selection("selection-staff", 100, 501, "PRINCIPAL_FIRST")))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/tools/b01/selection")
                        .headers(toolHeaders(1, "CUSTOMER", "wrong-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selection("selection-token", 100, 501, "PRINCIPAL_FIRST")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentSelectionsAreSerializedWithoutLostVersionUpdates() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown(); start.await(5, TimeUnit.SECONDS);
                return progress.saveSelectionForTool(1, 100,
                        new B01ProgressService.SelectionCommand("concurrent-1", 501, "PRINCIPAL_FIRST"));
            });
            var second = executor.submit(() -> {
                ready.countDown(); start.await(5, TimeUnit.SECONDS);
                return progress.saveSelectionForTool(1, 100,
                        new B01ProgressService.SelectionCommand("concurrent-2", 502, "INTEREST_FIRST"));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Set<Integer> versions = Set.of(first.get(10, TimeUnit.SECONDS).workItemVersion(),
                    second.get(10, TimeUnit.SECONDS).workItemVersion());
            assertThat(versions).containsExactlyInAnyOrder(1, 2);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select version from work_item where id=100", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select json_unquote(json_extract(draft_data,'$.selectedLoanId')) "
                + "from work_item where id=100", Long.class)).isIn(501L, 502L);
    }

    @Test
    void concurrentDuplicateSelectionCreatesOneReceiptAndOneVersionChange() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var command = new B01ProgressService.SelectionCommand(
                    "concurrent-same", 501, "PRINCIPAL_FIRST");
            var first = executor.submit(() -> {
                ready.countDown(); start.await(5, TimeUnit.SECONDS);
                return progress.saveSelectionForTool(1, 100, command);
            });
            var second = executor.submit(() -> {
                ready.countDown(); start.await(5, TimeUnit.SECONDS);
                return progress.saveSelectionForTool(1, 100, command);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(java.util.List.of(firstResult.changed(), secondResult.changed()))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(java.util.List.of(firstResult.replayed(), secondResult.replayed()))
                    .containsExactlyInAnyOrder(false, true);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select version from work_item where id=100", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from event_log where request_id=?", Integer.class,
                "b01-selection:concurrent-same")).isEqualTo(1);
    }

    @Test
    void savesAndRestoresSelectionThroughJwtContextApi() throws Exception {
        http.perform(put("/api/v1/customer/b01/work-items/{id}/context", 100)
                        .with(customer(1)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"context-1","loanId":501,
                                 "requestedRepaymentOrder":"PRINCIPAL_FIRST"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedLoanId").value(501))
                .andExpect(jsonPath("$.data.requestedRepaymentOrder").value("PRINCIPAL_FIRST"))
                .andExpect(jsonPath("$.data.currentStep").value("APPLICATION_UPLOAD"));

        http.perform(get("/api/v1/customer/b01/work-items/{id}/context", 100).with(customer(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedLoanId").value(501))
                .andExpect(jsonPath("$.data.applicationDocument").doesNotExist());

        http.perform(get("/api/v1/customer/b01/work-items/{id}/context", 100).with(customer(2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("WORK_ITEM_NOT_AVAILABLE"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customer(long id) {
        return jwt().jwt(token -> token.subject(Long.toString(id)).claim("role", "CUSTOMER"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static org.springframework.http.HttpHeaders toolHeaders(long actorId, String role, String token) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Actor-Id", Long.toString(actorId));
        headers.add("X-Actor-Role", role);
        headers.add("X-Tool-Gateway-Token", token);
        return headers;
    }

    private static String selection(String requestId, long workItemId, long loanId, String order) {
        return "{\"requestId\":\"" + requestId + "\",\"workItemId\":" + workItemId
                + ",\"selectedLoanId\":" + loanId + ",\"requestedRepaymentOrder\":\"" + order + "\"}";
    }

    private void assertRule(String requestId, String loanMutation, String requestedOrder,
                            String expectedReason) throws Exception {
        jdbc.update("update loan set acceleration_declared=true,outsourced=false,agreement_loan=false,"
                + "repayment_order='INTEREST_FIRST' where id=501");
        jdbc.update(loanMutation);
        progress.saveSelectionForTool(1, 100,
                new B01ProgressService.SelectionCommand(requestId + "-selection", 501, requestedOrder));
        http.perform(post("/api/v1/tools/b01/eligibility")
                        .headers(toolHeaders(1, "CUSTOMER", "b01-loan-tool-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"workItemId\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligibilityStatus").value("INELIGIBLE"))
                .andExpect(jsonPath("$.data.reasonCode").value(expectedReason));
    }
}
