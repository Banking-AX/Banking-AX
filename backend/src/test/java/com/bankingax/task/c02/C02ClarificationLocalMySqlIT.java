package com.bankingax.task.c02;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.jpa.WorkflowStartService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** STAFF-21 against real tables. Never point T2_VERIFY_DB_URL at the shared database: every test truncates. */
@EnabledIfEnvironmentVariable(named = "T2_VERIFY_DB_URL", matches = ".*banking_ax_t2_verify.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = {com.bankingax.BankingAxApplication.class, C02ClarificationLocalMySqlIT.Fakes.class})
class C02ClarificationLocalMySqlIT {
    private static final long CUSTOMER = 3, STAFF = 101, OTHER_STAFF = 103;
    static final ObjectMapper JSON = new ObjectMapper();
    static Map<String, Object> lastFacts;
    static volatile boolean agentFails;
    static volatile String lastQuestion;

    @TestConfiguration static class Fakes {
        @Bean @Primary StaffBriefPort fakeBrief() {
            return facts -> { lastFacts = facts; return JSON.createObjectNode().put("method", "RULE_FALLBACK").put("summary", "요약").put("customerDraft", "안내 초안"); };
        }
        @Bean @Primary StaffPolicyQuestionPort fakeQuestions() {
            return (question, taskCode) -> { lastQuestion = question; return JSON.createObjectNode().put("status", "ANSWERED").put("answer", "답").put("method", "LLM").put("promptVersion", "policy-question-1").put("retrievedAt", "2026-11-08T00:00:00Z")
                    .set("citations", JSON.createArrayNode().add(JSON.createObjectNode().put("docId", "DOC-A").put("section", "위임서류"))); };
        }
        @Bean @Primary StaffAgentPort fakeAgent() {
            return (requestId, caseId, revision, snapshot) -> agentFails
                    ? new StaffAgentPort.StaffAgentResponse("FAILED", null, List.of(), Map.of("code", "AGENT_DOWN"))
                    : new StaffAgentPort.StaffAgentResponse("SUCCEEDED", "READY_FOR_STAFF", List.of(), null);
        }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        String storage = Files.createTempDirectory("c02-review-").toString();
        registry.add("spring.datasource.url", () -> System.getenv("T2_VERIFY_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("APP_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("APP_DB_PASSWORD"));
        registry.add("app.case-port", () -> "jpa");
        registry.add("app.c02.document-port", () -> "jpa");
        registry.add("app.c02.document-provisioning-enabled", () -> "true");
        registry.add("app.c02.review-persistence-enabled", () -> "true");
        registry.add("app.c02.file-storage-root", () -> storage);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired C02IntakeService intake;
    @Autowired WorkflowStartService workflows;
    @Autowired C02ClarificationService clarifications;
    @Autowired C02StatementService statements;
    @Autowired C02StaffDecisionPersistence lifecycle;
    @Autowired C02StaffBriefService briefs;
    @Autowired C02PolicyQuestionService policyQuestions;
    long workItemId;
    String firstRequiredSlot;

    @BeforeEach void seed() {
        lastFacts = null;
        agentFails = false;
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks=0");
                for (String table : List.of("event_log", "notification", "staff_review", "review_item", "agent_review_run", "document_extraction", "document_file", "required_document", "heir", "clarification", "case_statement", "case_revision", "service_case", "work_item", "workflow", "user"))
                    statement.execute("truncate table `" + table + "`");
                statement.execute("set foreign_key_checks=1");
            }
            return null;
        });
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,can_pre_review) values(3,'c','x','CUSTOMER','c',false),(101,'s','x','STAFF','검토직원',true),(103,'o','x','STAFF','다른직원',true),(4,'c2','x','CUSTOMER','other',false)");
        workItemId = workflows.start(new WorkflowStartService.StartCommand("start-" + System.nanoTime(), CUSTOMER, TaskCode.INHERITANCE_DEPOSIT)).workItemId();
    }

    private long caseId() { return jdbc.queryForObject("select id from service_case where work_item_id=?", Long.class, workItemId); }
    private int revision() { return jdbc.queryForObject("select revision from service_case where work_item_id=?", Integer.class, workItemId); }
    private static C02IntakeService.HeirInput heir(String ref, boolean applicant, C02IntakeService.VisitStatus visit, C02IntakeService.DelegationMethod method) {
        return new C02IntakeService.HeirInput(ref, "name-" + ref, LocalDate.of(1990, 1, 1), "자녀", applicant, false, visit, method, null);
    }
    private void upload(String slot) {
        intake.uploadDocument(CUSTOMER, caseId(), revision(), "up-" + System.nanoTime(), slot, new MockMultipartFile("file", "x.pdf", "application/pdf", C02DemoSeeder.DemoPdf.of("x").getBytes()));
    }

    /** Submitted, review not started. */
    private long submitted() {
        var prepared = intake.prepare(CUSTOMER, workItemId, "prepare-" + System.nanoTime(), new C02IntakeService.Intake(
                new C02IntakeService.DecedentInput("피상속인", LocalDate.of(1950, 1, 1), LocalDate.of(2026, 1, 1), 0),
                List.of(heir("h1", true, C02IntakeService.VisitStatus.ATTENDING, C02IntakeService.DelegationMethod.NONE),
                        heir("h2", false, C02IntakeService.VisitStatus.ABSENT, C02IntakeService.DelegationMethod.DOCUMENT))), Map.of());
        for (var slot : new ArrayList<>(prepared.requiredDocuments())) if (slot.requirement() == C02IntakeService.Requirement.REQUIRED) { upload(slot.key()); firstRequiredSlot = slot.key(); }
        intake.recordConsentAndMockSignature(CUSTOMER, caseId(), revision(), true, true);
        intake.submitForDemoSeed(CUSTOMER, caseId(), revision(), "submit-" + System.nanoTime());
        return caseId();
    }

    private C02ClarificationService.Clarification ask(long id, String kind) {
        return clarifications.ask(STAFF, id, new C02ClarificationService.Ask(kind, "형님은 방문하시나요? 오지 못하면 알려 주세요.", "ask-" + System.nanoTime()));
    }
    private String code(Runnable call) { return assertThrows(DomainException.class, call::run).code(); }
    private String reviewStatus(long id) { return jdbc.queryForObject("select review_status from service_case where id=?", String.class, id); }

    @Test void aQuestionWaitsForTheCustomerAndNotifiesOnlyTheOwner() {
        long id = submitted();
        var asked = ask(id, "VISIT_ATTENDANCE");

        assertThat(asked.status()).isEqualTo("OPEN");
        assertThat(asked.askedByName()).isEqualTo("검토직원");
        assertThat(reviewStatus(id)).isEqualTo("WAITING_CUSTOMER");
        assertThat(jdbc.queryForObject("select count(*) from notification where type='C02_CLARIFICATION_REQUESTED' and customer_id=?", Integer.class, CUSTOMER)).isEqualTo(1);
        assertThat(clarifications.listForCustomer(CUSTOMER, id)).hasSize(1);
        assertThat(code(() -> clarifications.listForCustomer(4, id))).isEqualTo("FORBIDDEN");
    }

    @Test void theAnswerIsStoredAsWrittenAndTheCaseIsRecheckedOnANewRevision() {
        long id = submitted();
        int before = revision();
        var asked = ask(id, "VISIT_ATTENDANCE");
        var command = new C02ClarificationService.Answer("형은 해외에 있어서 못 가요", "answer-1");

        var answered = clarifications.answer(CUSTOMER, id, asked.id(), command);

        assertThat(answered.status()).isEqualTo("ANSWERED");
        assertThat(answered.answer()).isEqualTo("형은 해외에 있어서 못 가요");
        assertThat(revision()).isEqualTo(before + 1);
        assertThat(answered.resultRevision()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("select count(*) from agent_review_run where case_id=? and revision=?", Integer.class, id, before + 1)).isPositive();
        assertThat(reviewStatus(id)).as("review status after the re-check").isEqualTo("READY_FOR_STAFF");
        assertThat(clarifications.answer(CUSTOMER, id, asked.id(), command).id()).isEqualTo(asked.id());
        assertThat(code(() -> clarifications.answer(CUSTOMER, id, asked.id(), new C02ClarificationService.Answer("다시", "answer-2")))).isEqualTo("CLARIFICATION_CLOSED");
        assertThat(code(() -> clarifications.answer(4, id, asked.id(), new C02ClarificationService.Answer("남의 답", "answer-3")))).isEqualTo("FORBIDDEN");
    }

    private int runs(long id) { return jdbc.queryForObject("select count(*) from agent_review_run where case_id=? and check_type='STAFF_PRECHECK'", Integer.class, id); }

    @Test void aRetriedAnswerDoesNotRunTheRecheckAgain() {
        long id = submitted();
        var asked = ask(id, "DOCUMENT");
        var command = new C02ClarificationService.Answer("원본은 방문 때 가져갈게요", "answer-retry");
        clarifications.answer(CUSTOMER, id, asked.id(), command);
        int revisionAfter = revision(), runsAfter = runs(id);

        var again = clarifications.answer(CUSTOMER, id, asked.id(), command);

        assertThat(again.status()).isEqualTo("ANSWERED");
        assertThat(revision()).as("no second revision").isEqualTo(revisionAfter);
        assertThat(runs(id)).as("no second precheck run").isEqualTo(runsAfter);
        assertThat(jdbc.queryForObject("select count(*) from event_log where request_id='answer-retry'", Integer.class)).isEqualTo(1);
    }

    @Test void twoConcurrentCopiesOfOneAnswerRecheckOnce() throws Exception {
        long id = submitted();
        var asked = ask(id, "OTHER");
        int before = revision(), runsBefore = runs(id);
        var command = new C02ClarificationService.Answer("동시에 보낸 같은 답변", "answer-race");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<C02ClarificationService.Clarification>> calls = new ArrayList<>();
        for (int n = 0; n < 2; n++) calls.add(pool.submit(() -> { start.await(); return clarifications.answer(CUSTOMER, id, asked.id(), command); }));
        start.countDown();
        for (var call : calls) assertThat(call.get(60, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("ANSWERED");
        pool.shutdown();

        assertThat(revision()).isEqualTo(before + 1);
        assertThat(runs(id)).isEqualTo(runsBefore + 1);
        assertThat(jdbc.queryForObject("select count(*) from event_log where event_type='C02_CLARIFICATION_ANSWERED'", Integer.class)).isEqualTo(1);
    }

    @Test void theRecheckRunsWhenTheLastOpenQuestionIsAnswered() {
        long id = submitted();
        var first = ask(id, "VISIT_ATTENDANCE");
        var second = ask(id, "DOCUMENT");
        int before = revision(), runsBefore = runs(id);

        clarifications.answer(CUSTOMER, id, first.id(), new C02ClarificationService.Answer("방문할게요", "answer-a"));
        assertThat(revision()).as("one question still open: no re-check yet").isEqualTo(before);
        assertThat(reviewStatus(id)).isEqualTo("WAITING_CUSTOMER");

        clarifications.answer(CUSTOMER, id, second.id(), new C02ClarificationService.Answer("서류는 보완할게요", "answer-b"));
        assertThat(revision()).isEqualTo(before + 1);
        assertThat(runs(id)).isEqualTo(runsBefore + 1);
        assertThat(reviewStatus(id)).isEqualTo("READY_FOR_STAFF");
        assertThat(jdbc.queryForList("select result_revision from clarification where case_id=? order by id", Integer.class, id)).containsExactly(before + 1, before + 1);
    }

    @Test void aDecisionWithAnUnansweredQuestionIsRecordedNotBlocked() {
        long id = submitted();
        ask(id, "VISIT_ATTENDANCE");
        lifecycle.startReview(STAFF, id, revision(), "start-" + System.nanoTime());

        lifecycle.persist(STAFF, id, revision(), "decide-open-q", "REQUIREMENT_FAILED", "안내", "REVIEW_REJECTED_REQUIREMENT", null);

        assertThat(jdbc.queryForObject("select json_extract(payload,'$.openClarifications') from event_log where request_id='decide-open-q'", Integer.class)).isEqualTo(1);
    }

    @Test void aFailingRecheckKeepsTheAnswerAndHandsTheCaseToStaff() {
        long id = submitted();
        var asked = ask(id, "DOCUMENT");
        int before = revision();
        agentFails = true;

        var answered = clarifications.answer(CUSTOMER, id, asked.id(), new C02ClarificationService.Answer("에이전트가 실패해도 남아야 하는 답변", "answer-agent-down"));

        assertThat(answered.status()).isEqualTo("ANSWERED");
        assertThat(jdbc.queryForObject("select answer_text from clarification where id=?", String.class, asked.id())).isEqualTo("에이전트가 실패해도 남아야 하는 답변");
        assertThat(revision()).isEqualTo(before + 1);
        assertThat(reviewStatus(id)).isEqualTo("FAILED_NEEDS_STAFF");
    }

    private String requiredSlot() { return firstRequiredSlot; }

    @Test void aFileReplacedBeforeTheReviewMakesANewRevisionAndKeepsTheOldFileAndRun() {
        long id = submitted();
        int before = revision(), runsBefore = runs(id), filesBefore = jdbc.queryForObject("select count(*) from document_file", Integer.class);

        upload(requiredSlot());

        assertThat(revision()).isEqualTo(before + 1);
        assertThat(runs(id)).as("the earlier run stays and the new revision gets its own").isEqualTo(runsBefore + 1);
        assertThat(jdbc.queryForObject("select count(*) from document_file", Integer.class)).isEqualTo(filesBefore + 1);
        assertThat(reviewStatus(id)).isEqualTo("READY_FOR_STAFF");
    }

    @Test void aReplacedFileDoesNotCloseAnOpenQuestion() {
        long id = submitted();
        var asked = ask(id, "DOCUMENT");

        upload(requiredSlot());

        assertThat(reviewStatus(id)).isEqualTo("WAITING_CUSTOMER");
        clarifications.answer(CUSTOMER, id, asked.id(), new C02ClarificationService.Answer("새 파일로 올렸어요", "answer-after-upload"));
        assertThat(reviewStatus(id)).isEqualTo("READY_FOR_STAFF");
    }

    @Test void noFileCanBeReplacedOnceTheReviewStarted() {
        long id = submitted();
        lifecycle.startReview(STAFF, id, revision(), "start-" + System.nanoTime());

        assertThat(code(() -> upload(requiredSlot()))).isEqualTo("INVALID_STATE");
    }

    @Test void onlyTheOwnerCanReopenASubmittedFile() {
        long id = submitted();
        long file = jdbc.queryForObject("select min(id) from document_file", Long.class);

        assertThat(intake.customerDocument(CUSTOMER, id, file).metadata().documentFileId()).isEqualTo(file);
        assertThat(code(() -> intake.customerDocument(4, id, file))).isEqualTo("FORBIDDEN");
    }

    @Test void aPolicyQuestionRecordsItsEvidenceButNotItsWords() {
        long id = submitted();

        var answer = policyQuestions.ask(STAFF, id, "인감증명서 유효기간이 궁금해요", "policy-q-1");

        assertThat(answer.path("status").asText()).isEqualTo("ANSWERED");
        assertThat(lastQuestion).isEqualTo("인감증명서 유효기간이 궁금해요");
        String stored = jdbc.queryForObject("select cast(payload as char) from event_log where request_id='policy-q-1'", String.class);
        assertThat(stored).contains("DOC-A", "위임서류", "policy-question-1").doesNotContain("인감증명서 유효기간");
        assertThat(code(() -> policyQuestions.ask(STAFF, id, "주민번호 900101-1234567 고객은요?", "policy-q-2"))).isEqualTo("QUESTION_HAS_IDENTIFIER");
        assertThat(code(() -> policyQuestions.ask(STAFF, 99999, "다른 업무 질문이에요", "policy-q-3"))).isEqualTo("NOT_FOUND");
    }

    @Test void anUnansweredVisitQuestionExpiresAsProvisionalAbsenceOnly() {
        long id = submitted();
        var asked = ask(id, "VISIT_ATTENDANCE");
        jdbc.update("update clarification set due_at = UTC_TIMESTAMP(6) - interval 1 hour where id=?", asked.id());

        var listed = clarifications.listForStaff(id).get(0);

        assertThat(listed.status()).isEqualTo("EXPIRED");
        assertThat(listed.nonresponseAssumption()).isEqualTo("UNCONFIRMED_ABSENT");
        assertThat(reviewStatus(id)).isEqualTo("READY_FOR_STAFF");
        assertThat(code(() -> clarifications.answer(CUSTOMER, id, asked.id(), new C02ClarificationService.Answer("늦은 답", "late")))).isEqualTo("CLARIFICATION_CLOSED");
    }

    @Test void noQuestionAfterTheReviewStartsAndTheBriefGetsStatesNotWords() throws Exception {
        long id = submitted();
        var asked = ask(id, "DOCUMENT");
        clarifications.answer(CUSTOMER, id, asked.id(), new C02ClarificationService.Answer("고객이 쓴 비공개 답변", "answer-x"));
        assertThat(reviewStatus(id)).isEqualTo("READY_FOR_STAFF");
        briefs.generate(id, revision());
        String sent = JSON.writeValueAsString(lastFacts);
        assertThat(sent).contains("customerClarifications", "ANSWERED").doesNotContain("고객이 쓴 비공개 답변", "형님은 방문하시나요");

        lifecycle.startReview(STAFF, id, revision(), "start-" + System.nanoTime());
        assertThat(code(() -> ask(id, "OTHER"))).isEqualTo("CLARIFICATION_NOT_ALLOWED");
    }

    @Test void aStatementIsTheCustomersOwnTextKeptThirtyDaysAndReachesTheBriefOnlyAsACount() throws Exception {
        long id = submitted();
        var written = statements.write(CUSTOMER, id, new C02StatementService.Write("형은 해외에 있어 방문이 어려워요", "st-1"));
        assertThat(written.text()).isEqualTo("형은 해외에 있어 방문이 어려워요");
        assertThat(statements.write(CUSTOMER, id, new C02StatementService.Write("형은 해외에 있어 방문이 어려워요", "st-1")).id()).isEqualTo(written.id());
        assertThat(code(() -> statements.write(4, id, new C02StatementService.Write("남의 진술", "st-2")))).isEqualTo("FORBIDDEN");
        assertThat(statements.listForStaff(id)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from case_revision where cast(application_snapshot as char) like '%해외%'", Integer.class)).isZero();

        briefs.generate(id, revision());
        assertThat(JSON.writeValueAsString(lastFacts)).contains("\"customerStatementCount\":1").doesNotContain("해외");

        jdbc.update("update case_statement set expires_at = UTC_TIMESTAMP(6) - interval 1 second where id=?", written.id());
        assertThat(statements.listForStaff(id)).isEmpty();
        assertThat(statements.deleteExpired()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from case_statement", Integer.class)).isZero();
    }
}
