package com.bankingax.task.c02;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.jpa.WorkflowStartService;
import com.fasterxml.jackson.databind.JsonNode;
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

/** Draft edits and the review opinion against real tables. Never point T2_VERIFY_DB_URL at the shared database: every test truncates. */
@EnabledIfEnvironmentVariable(named = "T2_VERIFY_DB_URL", matches = ".*banking_ax_t2_verify.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = {com.bankingax.BankingAxApplication.class, C02DraftAndBriefLocalMySqlIT.Fakes.class})
class C02DraftAndBriefLocalMySqlIT {
    private static final long CUSTOMER = 3, STAFF = 101, NO_GRANT = 102;
    private static final String POA = "ABSENT_HEIR_POWER_OF_ATTORNEY", IDENTITY = "HEIR_IDENTITY_CARD";

    static final ObjectMapper JSON = new ObjectMapper();
    static Map<String, Object> lastFacts;
    static JsonNode briefAnswer;
    static RuntimeException briefFailure;

    @TestConfiguration static class Fakes {
        @Bean @Primary StaffBriefPort fakeBrief() {
            return facts -> {
                lastFacts = facts;
                if (briefFailure != null) throw briefFailure;
                return briefAnswer;
            };
        }
        @Bean @Primary StaffAgentPort fakeAgent() {
            return (requestId, caseId, revision, snapshot) -> new StaffAgentPort.StaffAgentResponse("SUCCEEDED", "READY_FOR_STAFF", List.of(), null);
        }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        String storage = Files.createTempDirectory("c02-draft-").toString();
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
    @Autowired C02StaffBriefService briefs;
    @Autowired C02ReviewerAccess reviewers;
    long workItemId;

    private static C02IntakeService.HeirInput heir(String ref, String name, boolean applicant, C02IntakeService.VisitStatus visit, C02IntakeService.DelegationMethod method) {
        return new C02IntakeService.HeirInput(ref, name, LocalDate.of(1990, 1, 1), "자녀", applicant, false, visit, method, null);
    }
    private static C02IntakeService.HeirInput attending(String ref, boolean applicant) { return heir(ref, "name-" + ref, applicant, C02IntakeService.VisitStatus.ATTENDING, C02IntakeService.DelegationMethod.NONE); }
    private static C02IntakeService.HeirInput absent(String ref) { return heir(ref, "name-" + ref, false, C02IntakeService.VisitStatus.ABSENT, C02IntakeService.DelegationMethod.DOCUMENT); }
    private static C02IntakeService.Intake form(C02IntakeService.HeirInput... heirs) {
        return new C02IntakeService.Intake(new C02IntakeService.DecedentInput("피상속인", LocalDate.of(1950, 1, 1), LocalDate.of(2026, 1, 1), 0), List.of(heirs));
    }

    @BeforeEach void seed() {
        lastFacts = null;
        briefFailure = null;
        briefAnswer = JSON.createObjectNode().put("method", "LLM").put("model", "test-model").put("promptVersion", "p1").put("summary", "요약").put("customerDraft", "안내 초안");
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks=0");
                for (String table : List.of("event_log", "notification", "staff_review", "review_item", "agent_review_run", "document_extraction", "document_file", "required_document", "heir", "case_revision", "service_case", "work_item", "workflow", "user"))
                    statement.execute("truncate table `" + table + "`");
                statement.execute("set foreign_key_checks=1");
            }
            return null;
        });
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,can_pre_review) values(3,'c','x','CUSTOMER','c',false),(101,'s','x','STAFF','s',true),(102,'n','x','STAFF','n',false)");
        workItemId = workflows.start(new WorkflowStartService.StartCommand("start-" + System.nanoTime(), CUSTOMER, TaskCode.INHERITANCE_DEPOSIT)).workItemId();
    }

    /** The service remembers requestIds across tests, so each call needs one the truncated tables never saw. */
    private C02IntakeService.Prepared prepare(C02IntakeService.Intake form) { return intake.prepare(CUSTOMER, workItemId, "prepare-" + System.nanoTime(), form, Map.of()); }
    private long caseId() { return jdbc.queryForObject("select id from service_case where work_item_id=?", Long.class, workItemId); }
    private int revision() { return jdbc.queryForObject("select revision from service_case where work_item_id=?", Integer.class, workItemId); }

    private List<String> heirRows() {
        return jdbc.queryForList("select concat(person_ref,'/',visit_status,'/',delegation_method,'/',is_applicant) from heir where case_id=? order by person_ref", String.class, caseId());
    }

    /** Slots the customer must act on: retired rows are not listed. */
    private List<String> activeSlots() {
        return jdbc.queryForList("""
                select concat(rd.document_type_code,':',coalesce(h.person_ref,'COMMON')) from required_document rd left join heir h on h.id=rd.heir_id
                where rd.work_item_id=? and rd.requirement<>'OPTIONAL' order by 1
                """, String.class, workItemId);
    }

    private void assertMatchesApplication(C02IntakeService.Prepared prepared) {
        assertThat(activeSlots()).containsExactlyInAnyOrderElementsOf(prepared.requiredDocuments().stream().map(C02IntakeService.DocumentSlot::key).toList());
        assertThat(jdbc.queryForObject("select revision from service_case where id=?", Integer.class, caseId())).isEqualTo(prepared.caseView().revision());
    }

    private void upload(String slot) throws Exception {
        intake.uploadDocument(CUSTOMER, caseId(), revision(), "up-" + System.nanoTime(), slot, new MockMultipartFile("file", "x.pdf", "application/pdf", C02DemoSeeder.DemoPdf.of("x").getBytes()));
    }

    private long fileCount() { return jdbc.queryForObject("select count(*) from document_file", Long.class); }
    private String slotStatus(String type, String ref) {
        return jdbc.queryForObject("select rd.status from required_document rd join heir h on h.id=rd.heir_id where rd.work_item_id=? and rd.document_type_code=? and h.person_ref=?", String.class, workItemId, type, ref);
    }

    @Test void addingAnHeirCreatesARealSlotThatAcceptsAnUpload() throws Exception {
        long id = prepare(form(attending("h1", true))).caseView().caseId();

        var edited = prepare(form(attending("h1", true), absent("h2")));

        assertThat(edited.caseView().caseId()).isEqualTo(id);
        assertMatchesApplication(edited);
        assertThat(heirRows()).hasSize(2);
        upload(POA + ":h2");
        assertThat(slotStatus(POA, "h2")).isEqualTo("UPLOADED");
    }

    @Test void removingAnHeirWithoutFilesRemovesItsRows() {
        prepare(form(attending("h1", true), absent("h2")));

        var edited = prepare(form(attending("h1", true)));

        assertMatchesApplication(edited);
        assertThat(heirRows()).containsExactly("h1/ATTENDING/NONE/1");
    }

    @Test void removingAnHeirWithFilesKeepsTheFilesButNotTheObligation() throws Exception {
        prepare(form(attending("h1", true), absent("h2")));
        upload(POA + ":h2");

        var edited = prepare(form(attending("h1", true)));

        assertMatchesApplication(edited);
        assertThat(fileCount()).isOne();
        assertThat(heirRows()).contains("h2/UNKNOWN/NONE/0");
        assertThat(edited.submission().missingDocuments()).doesNotContain(POA + ":h2");
        assertThat(jdbc.queryForObject("select condition_note from required_document where document_type_code=?", String.class, POA)).isEqualTo(C02HeirDocumentProvisioningService.RETIRED_NOTE);
    }

    @Test void theApplicantCanMoveToAnotherHeir() {
        prepare(form(attending("h1", true), attending("h2", false)));

        var edited = prepare(form(attending("h1", false), attending("h2", true)));

        assertMatchesApplication(edited);
        assertThat(heirRows()).containsExactly("h1/ATTENDING/NONE/0", "h2/ATTENDING/NONE/1");
    }

    @Test void switchingBetweenVisitAndWrittenDelegationRebuildsTheSlots() throws Exception {
        prepare(form(attending("h1", true), attending("h2", false)));
        upload(IDENTITY + ":h2");

        var delegated = prepare(form(attending("h1", true), absent("h2")));
        assertMatchesApplication(delegated);
        assertThat(activeSlots()).contains(POA + ":h2").doesNotContain(IDENTITY + ":h2");

        var back = prepare(form(attending("h1", true), attending("h2", false)));
        assertMatchesApplication(back);
        assertThat(slotStatus(IDENTITY, "h2")).as("the same person's file counts again").isEqualTo("UPLOADED");
        assertThat(back.submission().missingDocuments()).doesNotContain(IDENTITY + ":h2");
    }

    @Test void renamingSomeoneVoidsOnlyThatPersonsFilesWithoutDeletingThem() throws Exception {
        prepare(form(attending("h1", true), attending("h2", false)));
        upload(IDENTITY + ":h1");
        upload(IDENTITY + ":h2");

        var edited = prepare(form(attending("h1", true), heir("h2", "다른 사람", false, C02IntakeService.VisitStatus.ATTENDING, C02IntakeService.DelegationMethod.NONE)));

        assertMatchesApplication(edited);
        assertThat(slotStatus(IDENTITY, "h1")).isEqualTo("UPLOADED");
        assertThat(slotStatus(IDENTITY, "h2")).isEqualTo("NOT_SUBMITTED");
        assertThat(fileCount()).as("the stored file row stays").isEqualTo(2);
        assertThat(intake.documents(CUSTOMER, caseId())).hasSize(1);
        upload(IDENTITY + ":h2");
        assertThat(slotStatus(IDENTITY, "h2")).isEqualTo("UPLOADED");
    }

    @Test void changingWhoAPowerOfAttorneyDescribesRequiresANewOne() throws Exception {
        prepare(form(attending("h1", true), absent("h2")));
        upload(POA + ":h2");

        prepare(new C02IntakeService.Intake(new C02IntakeService.DecedentInput("다른 피상속인", LocalDate.of(1950, 1, 1), LocalDate.of(2026, 1, 1), 0), List.of(attending("h1", true), absent("h2"))));

        assertThat(slotStatus(POA, "h2")).isEqualTo("NOT_SUBMITTED");
    }

    @Test void repeatingOrReusingARequestKeepsItsMeaning() {
        var form = form(attending("h1", true));
        String same = "same-" + System.nanoTime();
        var first = intake.prepare(CUSTOMER, workItemId, same, form, Map.of());

        assertThat(intake.prepare(CUSTOMER, workItemId, same, form, Map.of()).caseView().revision()).isEqualTo(first.caseView().revision());
        assertThat(assertThrows(DomainException.class, () -> intake.prepare(CUSTOMER, workItemId, same, form(attending("h1", true), absent("h2")), Map.of())).code()).isEqualTo("DUPLICATE_REQUEST_CONFLICT");
        assertThat(heirRows()).hasSize(1);
    }

    @Test void anEditLeavesConsentToBeGivenAgain() throws Exception {
        prepare(form(attending("h1", true)));
        intake.recordConsentAndMockSignature(CUSTOMER, caseId(), revision(), true, true);

        prepare(form(attending("h1", true), attending("h2", false)));

        assertThat(assertThrows(DomainException.class, () -> intake.issueOtp(CUSTOMER, caseId(), revision())).code()).isEqualTo("CONSENT_OR_SIGNATURE_REQUIRED");
    }

    @Test void aSubmittedCaseCannotBeEditedThroughPrepare() throws Exception {
        submitted();
        List<String> before = heirRows();

        assertThat(assertThrows(DomainException.class, () -> prepare(form(attending("h1", true), absent("h2")))).code()).isEqualTo("INVALID_STATE");
        assertThat(heirRows()).isEqualTo(before);
    }

    private long submitted() throws Exception {
        var prepared = prepare(form(attending("h1", true), absent("h2")));
        for (var slot : new ArrayList<>(prepared.requiredDocuments())) if (slot.requirement() == C02IntakeService.Requirement.REQUIRED) upload(slot.key());
        intake.recordConsentAndMockSignature(CUSTOMER, caseId(), revision(), true, true);
        intake.submitForDemoSeed(CUSTOMER, caseId(), revision(), "submit-" + System.nanoTime());
        return caseId();
    }

    @Test void theReviewGrantIsReadFromTheAccountAndARevokedAccountLosesIt() {
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,can_pre_review) values(9101,'grant-on','x','STAFF','a',true),(9102,'grant-off','x','STAFF','b',false)");
        try {
            reviewers.require(9101);
            assertThrows(DomainException.class, () -> reviewers.require(9102));
            assertThrows(DomainException.class, () -> reviewers.require(9199));
            jdbc.update("update `user` set is_active=false where id=9101");
            assertThrows(DomainException.class, () -> reviewers.require(9101));
        } finally {
            jdbc.update("delete from `user` where id in (9101,9102)");
        }
    }

    @Test void theBriefUsesSettledFactsOnly() throws Exception {
        long id = submitted();

        JsonNode brief = briefs.generate(id, revision());

        assertThat(brief.path("status").asText()).isEqualTo("SUCCEEDED");
        String sent = JSON.writeValueAsString(lastFacts);
        assertThat(sent).contains("heirCount", "submitted").doesNotContain("name-h1", "name-h2", "피상속인", "x.pdf", "1990");
        assertThat(((Map<?, ?>) lastFacts.get("verification")).get("matched")).asList().isEmpty();
        assertThat(((Map<?, ?>) lastFacts.get("verification")).get("ocrStatus")).isEqualTo("NOT_RUN");
    }

    @Test void theBriefIsStoredWithoutTouchingTheCase() throws Exception {
        long id = submitted();
        String before = jdbc.queryForObject("select concat_ws('|',case_status,review_status,revision,version) from service_case where id=?", String.class, id);

        briefs.generate(id, revision());

        assertThat(briefs.latest(id).orElseThrow().path("customerDraft").asText()).isEqualTo("안내 초안");
        assertThat(jdbc.queryForObject("select concat_ws('|',case_status,review_status,revision,version) from service_case where id=?", String.class, id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from notification where type<>'CASE_RECEIVED'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from staff_review", Integer.class)).isZero();
    }

    /** The 2026-11-09 run found one row per revision being overwritten, so the opinion before a policy change was lost. */
    @Test void everyGenerationStaysInHistoryWhileTheLatestRowIsReplaced() throws Exception {
        long id = submitted();
        briefs.generate(id, revision(), 9L);
        briefs.generate(id, revision(), 9L);
        briefFailure = new DomainException("STAFF_AGENT_UNAVAILABLE", "down", true);
        briefs.generate(id, revision(), 9L);

        assertThat(briefs.history(id)).as("two successes, and the failure leaves no entry").hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from agent_review_run where case_id=? and check_type='STAFF_BRIEF'", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from event_log where entity_type='STAFF_BRIEF' and entity_id=? and actor_type='STAFF' and actor_id=9", Integer.class, String.valueOf(id))).isEqualTo(2);
    }

    @Test void aFailedAgentKeepsTheEarlierOpinion() throws Exception {
        long id = submitted();
        briefs.generate(id, revision());
        briefFailure = new DomainException("STAFF_AGENT_UNAVAILABLE", "down", true);

        assertThat(briefs.generate(id, revision()).path("status").asText()).isEqualTo("FAILED");

        assertThat(briefs.latest(id).orElseThrow().path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test void anUnusableAnswerIsRefusedAndForeignBasisRemoved() throws Exception {
        long id = submitted();
        briefAnswer = JSON.readTree("{\"method\":\"MADE_UP\",\"summary\":\"x\",\"customerDraft\":\"y\"}");
        assertThat(briefs.generate(id, revision()).path("code").asText()).isEqualTo("STAFF_AGENT_RESPONSE_INVALID");

        briefAnswer = JSON.readTree("{\"method\":\"LLM\",\"summary\":\"x\",\"customerDraft\":\"y\",\"evidence\":[{\"docId\":\"C02-01\"}],\"basis\":[{\"docId\":\"C02-01\",\"reason\":\"r\"},{\"docId\":\"FAKE\",\"reason\":\"r\"}]}");
        assertThat(briefs.generate(id, revision()).path("basis")).hasSize(1);
    }

    @Test void aStaleRevisionADecidedCaseAndAnAccountWithoutTheGrantAreRefused() throws Exception {
        long id = submitted();
        assertThat(assertThrows(DomainException.class, () -> briefs.generate(id, revision() - 1)).code()).isEqualTo("VERSION_CONFLICT");
        assertThat(assertThrows(DomainException.class, () -> briefs.requireReviewer(NO_GRANT)).code()).isEqualTo("FORBIDDEN");
        briefs.requireReviewer(STAFF);

        jdbc.update("update service_case set case_status='REVIEW_PASSED' where id=?", id);
        assertThat(assertThrows(DomainException.class, () -> briefs.generate(id, revision())).code()).isIn("INVALID_STATE", "CASE_ALREADY_DECIDED");
    }
}
