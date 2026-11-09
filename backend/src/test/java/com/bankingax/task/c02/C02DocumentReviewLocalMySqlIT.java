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

/** STAFF-06 against real tables. Never point T2_VERIFY_DB_URL at the shared database: every test truncates. */
@EnabledIfEnvironmentVariable(named = "T2_VERIFY_DB_URL", matches = ".*banking_ax_t2_verify.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = {com.bankingax.BankingAxApplication.class, C02DocumentReviewLocalMySqlIT.Fakes.class})
class C02DocumentReviewLocalMySqlIT {
    private static final long CUSTOMER = 3, STAFF = 101, OTHER_STAFF = 103;
    static final ObjectMapper JSON = new ObjectMapper();
    static Map<String, Object> lastFacts;

    @TestConfiguration static class Fakes {
        @Bean @Primary StaffBriefPort fakeBrief() {
            return facts -> { lastFacts = facts; return JSON.createObjectNode().put("method", "RULE_FALLBACK").put("summary", "요약").put("customerDraft", "안내 초안"); };
        }
        @Bean @Primary StaffAgentPort fakeAgent() {
            return (requestId, caseId, revision, snapshot) -> new StaffAgentPort.StaffAgentResponse("SUCCEEDED", "READY_FOR_STAFF", List.of(), null);
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
    @Autowired C02DocumentReviewService reviews;
    @Autowired C02StaffDecisionPersistence lifecycle;
    @Autowired C02StaffBriefService briefs;
    long workItemId;

    @BeforeEach void seed() {
        lastFacts = null;
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks=0");
                for (String table : List.of("event_log", "notification", "staff_review", "review_item", "agent_review_run", "document_extraction", "document_file", "required_document", "heir", "case_revision", "service_case", "work_item", "workflow", "user"))
                    statement.execute("truncate table `" + table + "`");
                statement.execute("set foreign_key_checks=1");
            }
            return null;
        });
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,can_pre_review) values(3,'c','x','CUSTOMER','c',false),(101,'s','x','STAFF','검토직원',true),(103,'o','x','STAFF','다른직원',true)");
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

    /** Submitted and under review by STAFF. */
    private long inReview() {
        var prepared = intake.prepare(CUSTOMER, workItemId, "prepare-" + System.nanoTime(), new C02IntakeService.Intake(
                new C02IntakeService.DecedentInput("피상속인", LocalDate.of(1950, 1, 1), LocalDate.of(2026, 1, 1), 0),
                List.of(heir("h1", true, C02IntakeService.VisitStatus.ATTENDING, C02IntakeService.DelegationMethod.NONE),
                        heir("h2", false, C02IntakeService.VisitStatus.ABSENT, C02IntakeService.DelegationMethod.DOCUMENT))), Map.of());
        for (var slot : new ArrayList<>(prepared.requiredDocuments())) if (slot.requirement() == C02IntakeService.Requirement.REQUIRED) upload(slot.key());
        intake.recordConsentAndMockSignature(CUSTOMER, caseId(), revision(), true, true);
        intake.submitForDemoSeed(CUSTOMER, caseId(), revision(), "submit-" + System.nanoTime());
        lifecycle.startReview(STAFF, caseId(), revision(), "start-" + System.nanoTime());
        return caseId();
    }

    private C02DocumentReviewService.Row first(long caseId) {
        return reviews.overview(caseId).documents().stream().filter(row -> row.documentFileId() != null).findFirst().orElseThrow();
    }
    private C02DocumentReviewService.Row save(long staff, long caseId, C02DocumentReviewService.Row row, String result, String reason, Long expected) {
        return reviews.review(staff, caseId, row.documentFileId(), new C02DocumentReviewService.Command(row.fileVersion(), result, reason, expected, "rv-" + System.nanoTime()));
    }
    private String code(Runnable call) { return assertThrows(DomainException.class, call::run).code(); }

    private String decide(long id, String outcome, String reason) {
        String target = switch (outcome) { case "PROCEED" -> "REVIEW_PASSED"; case "REQUIREMENT_FAILED" -> "REVIEW_REJECTED_REQUIREMENT"; default -> "REVIEW_REJECTED_DOCUMENT"; };
        lifecycle.persist(STAFF, id, revision(), "decide-" + System.nanoTime(), outcome, "안내", target, reason);
        return jdbc.queryForObject("select cast(payload as char) from event_log where event_type='C02_STAFF_DECIDED' order by id desc limit 1", String.class);
    }

    @Test void proceedWithOpenChecksNeedsAnExceptionReasonThatIsRecorded() throws Exception {
        long id = inReview();
        save(STAFF, id, first(id), "SUPPLEMENT_REQUIRED", "발급일 초과", null);
        assertThat(code(() -> decide(id, "PROCEED", null))).isEqualTo("DOCUMENT_CHECKS_OPEN");
        assertThat(jdbc.queryForObject("select case_status from service_case where id=?", String.class, id)).isEqualTo("IN_REVIEW");

        var payload = JSON.readTree(decide(id, "PROCEED", "원본은 방문 때 다시 확인하기로 함"));

        assertThat(payload.path("exceptionReason").asText()).isEqualTo("원본은 방문 때 다시 확인하기로 함");
        assertThat(payload.path("openSupplement").asInt()).isEqualTo(1);
        assertThat(payload.path("openUnreviewed").asInt()).isPositive();
    }

    @Test void rejectionsAreNotGatedAndAFullyCheckedProceedStoresNoException() throws Exception {
        long id = inReview();
        assertThat(JSON.readTree(decide(id, "DOCUMENT_INSUFFICIENT", null)).path("exceptionReason").isNull()).isTrue();

        jdbc.update("update service_case set case_status='IN_REVIEW', decided_at=null where id=?", id);
        jdbc.update("update staff_review set decided_at=null, result=null where case_id=?", id);
        jdbc.update("delete from notification where case_id=? and type<>'CASE_RECEIVED'", id);
        for (var row : reviews.overview(id).documents()) if (row.documentFileId() != null) save(STAFF, id, row, "CONFIRMED", null, null);
        var payload = JSON.readTree(decide(id, "PROCEED", "필요 없는 사유"));
        assertThat(payload.path("exceptionReason").isNull()).isTrue();
        assertThat(payload.path("openUnreviewed").asInt()).isZero();
    }

    @Test void theCaseTimelineListsStartChecksAndDecisionInOrder() {
        long id = inReview();
        save(STAFF, id, first(id), "CONFIRMED", null, null);
        decide(id, "DOCUMENT_INSUFFICIENT", null);

        var kinds = reviews.timeline(id).stream().map(C02DocumentReviewService.TimelineEntry::kind).toList();
        assertThat(kinds).containsSubsequence("C02_REVIEW_STARTED", "DOCUMENT_STAFF_REVIEWED", "C02_STAFF_DECIDED");
        assertThat(reviews.timeline(id).get(kinds.indexOf("C02_REVIEW_STARTED")).actorName()).isEqualTo("검토직원");
    }

    @Test void aDecisionIsStoredReloadedAndKeptInHistory() {
        long id = inReview();
        var overview = reviews.overview(id);
        assertThat(overview.reviewerName()).isEqualTo("검토직원");
        assertThat(overview.summary().confirmed()).isZero();
        assertThat(overview.summary().unreviewed()).isEqualTo(overview.summary().submitted()).isPositive();
        assertThat(overview.visitOnly()).isNotEmpty().allMatch(row -> row.documentFileId() == null);

        var saved = save(STAFF, id, first(id), "CONFIRMED", null, null);

        assertThat(saved.review().result()).isEqualTo("CONFIRMED");
        assertThat(saved.review().staffName()).isEqualTo("검토직원");
        var reloaded = reviews.overview(id);
        assertThat(reloaded.summary().confirmed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select concat_ws('|',status,human_checked_by) from required_document where id=?", String.class, saved.requiredDocumentId())).isEqualTo("HUMAN_VERIFIED|101");
        assertThat(reviews.history(id)).hasSize(1).first().satisfies(entry -> {
            assertThat(entry.previousResult()).isEqualTo("NOT_REVIEWED");
            assertThat(entry.currentFile()).isTrue();
        });
    }

    @Test void aSupplementNeedsAReasonAndAChangeKeepsTheEarlierDecision() {
        long id = inReview();
        var row = first(id);
        assertThat(code(() -> save(STAFF, id, row, "SUPPLEMENT_REQUIRED", "  ", null))).isEqualTo("VALIDATION_ERROR");
        var supplement = save(STAFF, id, row, "SUPPLEMENT_REQUIRED", "발급일이 3개월을 넘었어요", null);
        assertThat(supplement.review().reason()).isEqualTo("발급일이 3개월을 넘었어요");

        var confirmed = save(STAFF, id, row, "CONFIRMED", null, supplement.review().eventId());

        assertThat(confirmed.review().result()).isEqualTo("CONFIRMED");
        assertThat(reviews.history(id)).extracting(C02DocumentReviewService.HistoryEntry::result).containsExactly("CONFIRMED", "SUPPLEMENT_REQUIRED");
        assertThat(reviews.history(id).get(0).previousResult()).isEqualTo("SUPPLEMENT_REQUIRED");
    }

    @Test void aDecisionMadeInBetweenIsNotOverwritten() {
        long id = inReview();
        var row = first(id);
        save(STAFF, id, row, "CONFIRMED", null, null);

        assertThat(code(() -> save(STAFF, id, row, "SUPPLEMENT_REQUIRED", "사유", null))).isEqualTo("REVIEW_CONFLICT");
        assertThat(code(() -> reviews.review(STAFF, id, row.documentFileId(), new C02DocumentReviewService.Command(row.fileVersion() + 1, "CONFIRMED", null, null, "rv-x")))).isEqualTo("DOCUMENT_STALE");
    }

    @Test void onlyTheReviewerOfAStartedOpenCaseMayDecide() {
        var prepared = intake.prepare(CUSTOMER, workItemId, "prepare-" + System.nanoTime(), new C02IntakeService.Intake(
                new C02IntakeService.DecedentInput("피상속인", LocalDate.of(1950, 1, 1), LocalDate.of(2026, 1, 1), 0),
                List.of(heir("h1", true, C02IntakeService.VisitStatus.ATTENDING, C02IntakeService.DelegationMethod.NONE))), Map.of());
        for (var slot : new ArrayList<>(prepared.requiredDocuments())) if (slot.requirement() == C02IntakeService.Requirement.REQUIRED) upload(slot.key());
        intake.recordConsentAndMockSignature(CUSTOMER, caseId(), revision(), true, true);
        intake.submitForDemoSeed(CUSTOMER, caseId(), revision(), "submit-" + System.nanoTime());
        long id = caseId();
        var row = first(id);
        assertThat(code(() -> save(STAFF, id, row, "CONFIRMED", null, null))).isEqualTo("REVIEW_NOT_STARTED");

        lifecycle.startReview(STAFF, id, revision(), "start-" + System.nanoTime());
        assertThat(code(() -> save(OTHER_STAFF, id, row, "CONFIRMED", null, null))).isEqualTo("FORBIDDEN");

        jdbc.update("update service_case set case_status='REVIEW_PASSED' where id=?", id);
        assertThat(code(() -> save(STAFF, id, row, "CONFIRMED", null, null))).isEqualTo("CASE_ALREADY_DECIDED");
    }

    @Test void aNewFileStartsUncheckedAndTheOldDecisionStaysInHistory() {
        long id = inReview();
        var row = first(id);
        save(STAFF, id, row, "CONFIRMED", null, null);
        String slot = row.documentTypeCode() + ":" + (row.personRef() == null ? "COMMON" : row.personRef());

        // The customer cannot upload while the review runs; a supplement goes through the staff result and a new Case.
        assertThat(assertThrows(DomainException.class, () -> upload(slot)).getMessage()).contains("revised");
        // Any replacement that does reach the slot (a new version becoming current) starts unchecked.
        jdbc.update("update document_file set is_latest=false where id=?", row.documentFileId());
        jdbc.update("insert into document_file(required_document_id,version,is_latest,original_name,extension,size_bytes,storage_path,content_type,case_revision,uploaded_by) values(?,?,true,'y.pdf','pdf',10,'replaced','application/pdf',?,?)",
                row.requiredDocumentId(), row.fileVersion() + 1, revision(), CUSTOMER);

        var replaced = reviews.overview(id).documents().stream().filter(item -> item.requiredDocumentId() == row.requiredDocumentId()).findFirst().orElseThrow();
        assertThat(replaced.documentFileId()).isNotEqualTo(row.documentFileId());
        assertThat(replaced.review()).isNull();
        assertThat(reviews.overview(id).summary().confirmed()).isZero();
        assertThat(reviews.history(id)).hasSize(1).first().satisfies(entry -> assertThat(entry.currentFile()).isFalse());
        assertThat(code(() -> save(STAFF, id, row, "CONFIRMED", null, null))).isEqualTo("DOCUMENT_STALE");
    }

    @Test void aFileOfAnotherCaseIsNotFoundAndARepeatedRequestIsNotDoubled() {
        long id = inReview();
        var row = first(id);
        assertThat(code(() -> reviews.review(STAFF, id + 999, row.documentFileId(), new C02DocumentReviewService.Command(row.fileVersion(), "CONFIRMED", null, null, "rv-y")))).isEqualTo("NOT_FOUND");
        var command = new C02DocumentReviewService.Command(row.fileVersion(), "CONFIRMED", null, null, "same-request");
        reviews.review(STAFF, id, row.documentFileId(), command);
        reviews.review(STAFF, id, row.documentFileId(), command);
        assertThat(reviews.history(id)).hasSize(1);
    }

    @Test void theQueueCountsStaffChecksApartFromOcrAndTheBriefReceivesThemWithoutReasons() throws Exception {
        long id = inReview();
        var rows = reviews.overview(id).documents().stream().filter(item -> item.documentFileId() != null).toList();
        save(STAFF, id, rows.get(0), "CONFIRMED", null, null);
        save(STAFF, id, rows.get(1), "SUPPLEMENT_REQUIRED", "비공개 사유-테스트", null);

        var summary = reviews.summaries(List.of(id)).get(id);
        assertThat(summary.confirmed()).isEqualTo(1);
        assertThat(summary.supplement()).isEqualTo(1);
        assertThat(summary.unreviewed()).isEqualTo(rows.size() - 2);
        assertThat(summary.lastReviewedAt()).isNotNull();

        briefs.generate(id, revision());
        String sent = JSON.writeValueAsString(lastFacts);
        assertThat(sent).contains("staffDocumentChecks", "CONFIRMED", "SUPPLEMENT_REQUIRED", "NOT_REVIEWED").doesNotContain("비공개 사유-테스트", "검토직원");
    }
}
