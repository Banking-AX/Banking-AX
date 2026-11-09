package com.bankingax.core.policychange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Register, gate and extension behaviour on a disposable Flyway schema. Never point T2_VERIFY_DB_URL at the shared
 * banking_ax database: every test truncates.
 */
@EnabledIfEnvironmentVariable(named = "T2_VERIFY_DB_URL", matches = ".*banking_ax_t2_verify.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = {com.bankingax.BankingAxApplication.class, PolicyChangeLocalMySqlIT.Fakes.class})
class PolicyChangeLocalMySqlIT {
    private static final long REVIEWER = 1, NO_GRANT = 2;
    private static final String C02 = "INHERITANCE_DEPOSIT", B02 = "HOUSING_SAVINGS_CLOSE", NEW_TASK = "FX_REMITTANCE";

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("T2_VERIFY_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("APP_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("APP_DB_PASSWORD"));
        registry.add("app.case-port", () -> "jpa");
        registry.add("app.policy-change.provider", () -> "test");
    }

    /** A second bank: different field names and a different transport shape behind the same seam. */
    static class FakeProvider implements PolicyChangeProvider {
        final List<JsonNode> queue = new ArrayList<>();
        final ObjectMapper json = new ObjectMapper();
        DomainException failure;
        final List<String> cursors = new ArrayList<>();

        @Override public String sourceSystem() { return "SECOND_BANK"; }

        @Override public Feed fetch(String cursor) {
            cursors.add(cursor);
            if (failure != null) throw failure;
            List<JsonNode> canonical = queue.stream().map(this::toCanonical).toList();
            queue.clear();
            return new Feed(canonical, "cursor-" + cursors.size());
        }

        private JsonNode toCanonical(JsonNode native_) {
            if (!native_.has("rule")) return native_;
            ObjectNode out = json.createObjectNode().put("sourceSystem", "SECOND_BANK").put("changeEventId", native_.path("msg").asText())
                    .put("externalPolicyId", native_.path("rule").asText()).put("sourceVersion", "r" + native_.path("rev").asInt())
                    .put("versionOrder", native_.path("rev").asInt()).put("policyCategory", "OPERATION_GUIDE")
                    .put("changeType", native_.path("kind").asText()).put("publishedAt", "2026-01-01T00:00:00Z")
                    .put("effectiveAt", native_.path("from").asText()).put("sourceStatus", "PUBLISHED").put("changeSummary", native_.path("title").asText());
            out.putArray("affectedTaskCodes").add(native_.path("task").asText());
            out.putObject("sourceReference").put("documentId", "DOC");
            out.set("detail", native_.path("detail"));
            if (native_.has("prev")) out.put("previousVersion", native_.path("prev").asText());
            return out;
        }
    }

    static class FakeAgent implements PolicyAgentPort {
        JsonNode response;
        DomainException failure;
        Map<String, Object> lastRequest;

        @Override public JsonNode analyze(Map<String, Object> request) {
            lastRequest = request;
            if (failure != null) throw failure;
            return response;
        }
    }

    /** What a developer adds for a new task that wants its own narrowing: this one bean. */
    static class NewTaskProfile implements PolicyTaskProfile {
        @Override public String taskCode() { return NEW_TASK; }
        @Override public List<String> validateExtension(JsonNode extension) {
            return extension.path("currency").isTextual() || extension.isMissingNode() ? List.of() : List.of("FX_EXTENSION_INVALID");
        }
    }

    @TestConfiguration static class Fakes {
        @Bean FakeProvider fakeProvider() { return new FakeProvider(); }
        @Bean @Primary FakeAgent fakeAgent() { return new FakeAgent(); }
        @Bean NewTaskProfile newTaskProfile() { return new NewTaskProfile(); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PolicyChangeIngestService ingest;
    @Autowired PolicyChangeReviewService review;
    @Autowired PolicyChangeStore store;
    @Autowired PolicyTaskRegistry registry;
    @Autowired FakeProvider provider;
    @Autowired FakeAgent agent;
    @Autowired Environment environment;

    @BeforeEach void reset() {
        provider.queue.clear();
        provider.failure = null;
        provider.cursors.clear();
        agent.failure = null;
        agent.response = null;
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks=0");
                for (String table : List.of("event_log", "required_document", "heir", "case_revision", "service_case", "work_item", "workflow", "user"))
                    statement.execute("truncate table `" + table + "`");
                statement.execute("set foreign_key_checks=1");
            }
            return null;
        });
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,can_pre_review) values "
                + "(1,'reviewer','x','STAFF','r',true),(2,'no-grant','x','STAFF','n',false),(3,'customer','x','CUSTOMER','c',false)");
        jdbc.update("insert into workflow(id,customer_id,status) values(10,3,'RUNNING')");
        seedCase(101, "RECEIPT", "ABSENT", "SUBMITTED");
        seedCase(102, "NONE", "ATTENDING", "SUBMITTED");
        seedCase(103, "RECEIPT", "ABSENT", "REVIEW_PASSED");
        seedCase(104, "DOCUMENT", "UNCONFIRMED_ABSENT", "IN_REVIEW");
    }

    private void seedCase(long id, String method, String visit, String status) {
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values(?,10,?,?,'HANDED_OFF',?)", id, C02, id, "work-" + id);
        jdbc.update("insert into service_case(id,work_item_id,customer_id,case_type,case_status,revision,visit_required,application,submitted_at) "
                + "values(?,?,3,?,?,1,true,'{}',UTC_TIMESTAMP(6))", id, id, C02, status);
        jdbc.update("insert into case_revision(case_id,revision,application_snapshot,change_reason) values(?,1,'{}','SUBMIT')", id);
        jdbc.update("insert into heir(case_id,person_ref,name,relationship,is_applicant,visit_status,delegation_method) values(?,'H-1','n','CHILD',false,?,?)", id, visit, method);
        jdbc.update("insert into required_document(work_item_id,document_type_code,requirement,rule_version) values(?,'DECEDENT_BASIC_CERTIFICATE','REQUIRED','c02-g1')", id);
    }

    private ObjectNode event(String id, String task, int order, String previous) {
        ObjectNode event = json.createObjectNode().put("sourceSystem", "SECOND_BANK").put("changeEventId", id)
                .put("externalPolicyId", "POLICY-" + task).put("sourceVersion", "v" + order).put("versionOrder", order)
                .put("policyCategory", "OPERATION_GUIDE").put("changeType", "REVIEW_CRITERIA_CHANGE")
                .put("publishedAt", "2026-01-01T00:00:00Z").put("effectiveAt", Instant.now().plus(7, ChronoUnit.DAYS).toString())
                .put("sourceStatus", "PUBLISHED").put("changeSummary", "[교육용 가상 사례] summary " + id);
        if (previous != null) event.put("previousVersion", previous);
        event.putArray("affectedTaskCodes").add(task);
        event.putObject("sourceReference").put("documentId", "DOC");
        event.putObject("detail").put("before", "before").put("after", "after").putObject("taskExtensions");
        return event;
    }

    private ObjectNode c02Event(String id, int order, String previous) {
        ObjectNode event = event(id, C02, order, previous);
        ObjectNode extension = ((ObjectNode) event.path("detail").path("taskExtensions")).putObject(C02);
        extension.putArray("delegationMethods").add("RECEIPT");
        return event;
    }

    private PolicyChangeIngestService.SyncResult sync(JsonNode... events) {
        provider.queue.addAll(List.of(events));
        return ingest.sync(REVIEWER, "sync-" + provider.cursors.size());
    }

    private long onlyChange(String task) {
        return review.overview(REVIEWER).changes().stream().filter(row -> row.change().affectedTaskCodes().contains(task))
                .findFirst().orElseThrow().id();
    }

    private String caseFingerprint() {
        return jdbc.queryForObject("select md5(group_concat(concat_ws('|',id,case_status,revision,version,updated_at) order by id)) from service_case", String.class);
    }

    private String ruleDataFingerprint() {
        return jdbc.queryForObject("""
                select md5(concat((select group_concat(concat_ws('|',id,document_type_code,requirement,rule_version,status) order by id) from required_document),
                                  (select group_concat(concat_ws('|',code,valid_days,ocr_enabled,is_active) order by code) from document_type)))
                """, String.class);
    }

    @Test void theSameEventTwiceIsStoredOnce() {
        ObjectNode event = c02Event("E1", 2, null);

        assertThat(sync(event).accepted()).isOne();
        assertThat(sync(event.deepCopy()).duplicate()).isOne();

        assertThat(review.overview(REVIEWER).changes()).hasSize(1);
    }

    @Test void theSameEventIdWithDifferentContentIsQuarantined() {
        sync(c02Event("E1", 2, null));

        var result = sync(c02Event("E1", 2, null).put("changeSummary", "altered"));

        assertThat(result.quarantined()).isOne();
        assertThat(review.overview(REVIEWER).changes().get(0).change().changeSummary()).doesNotContain("altered");
        assertThat(review.overview(REVIEWER).operations()).anyMatch(row -> "EVENT_CONTENT_CONFLICT".equals(row.value()));
    }

    @Test void anOlderVersionArrivingLateNeverReplacesTheCurrentOne() {
        sync(c02Event("E3", 3, "v2"));

        var result = sync(c02Event("E2", 2, "v1"));

        assertThat(result.stale()).isOne();
        var rows = review.overview(REVIEWER).changes();
        assertThat(rows.stream().filter(row -> row.change().versionOrder() == 2)).allMatch(row -> "STALE_VERSION".equals(row.reviewStatus()));
        assertThat(rows.stream().filter(row -> row.change().versionOrder() == 3)).allMatch(row -> "PENDING_REVIEW".equals(row.reviewStatus()));
        long stale = rows.stream().filter(row -> row.change().versionOrder() == 2).findFirst().orElseThrow().id();
        assertThat(assertThrows(DomainException.class, () -> review.decide(REVIEWER, stale, PolicyChangeReviewService.Decision.NOTICE_APPROVED, null)).code())
                .isEqualTo("POLICY_CHANGE_NOT_REVIEWABLE");
    }

    @Test void aSkippedVersionIsFlaggedForStaff() {
        sync(c02Event("E2", 2, "v1"));
        sync(c02Event("E5", 5, "v4"));

        assertThat(review.overview(REVIEWER).changes().get(0).versionGap()).isTrue();
    }

    @Test void aNewerVersionSupersedesAnUndecidedOne() {
        sync(c02Event("E2", 2, "v1"), c02Event("E3", 3, "v2"));

        var byOrder = review.overview(REVIEWER).changes();
        assertThat(byOrder.get(0).reviewStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(byOrder.get(1).reviewStatus()).isEqualTo("SUPERSEDED");
    }

    @Test void aTaskNotHandledHereIsRecordedOutOfScopeAndNeverReachesReview() {
        var result = sync(event("E1", "CARD_ISSUE", 1, null));

        assertThat(result.outOfScope()).isOne();
        var row = review.overview(REVIEWER).changes().get(0);
        assertThat(row.reviewStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(row.unmappedTaskCodes()).containsExactly("CARD_ISSUE");
        assertThat(assertThrows(DomainException.class, () -> review.analyze(REVIEWER, row.id(), "a")).code()).isEqualTo("POLICY_CHANGE_NOT_REVIEWABLE");
    }

    @Test void aPartlyKnownEventKeepsTheUnknownCodesVisible() {
        ObjectNode event = c02Event("E1", 2, null);
        ((com.fasterxml.jackson.databind.node.ArrayNode) event.path("affectedTaskCodes")).add("CARD_ISSUE");

        sync(event);

        var row = review.overview(REVIEWER).changes().get(0);
        assertThat(row.mappedTaskCodes()).containsExactly(C02);
        assertThat(row.unmappedTaskCodes()).containsExactly("CARD_ISSUE");
    }

    @Test void invalidEventsAreQuarantinedWithoutBlockingValidOnes() {
        var result = sync(event("BAD", C02, 1, null).put("changeType", "AUTO_APPLY_RULE"), event("WRONG-SOURCE", C02, 1, null).put("sourceSystem", "SPOOFED"),
                c02Event("GOOD", 2, null));

        assertThat(result.quarantined()).isEqualTo(2);
        assertThat(result.accepted()).isOne();
        assertThat(review.overview(REVIEWER).operations().stream().map(PolicyChangeStore.Entry::value))
                .contains("UNKNOWN_CHANGE_TYPE", "SOURCE_MISMATCH");
    }

    @Test void aTaskProfileRejectsItsOwnBadExtension() {
        ObjectNode event = c02Event("E1", 2, null);
        ((ObjectNode) event.path("detail").path("taskExtensions").path(C02)).putArray("delegationMethods").add("TELEPATHY");

        assertThat(sync(event).quarantined()).isOne();
    }

    @Test void aFeedFailureIsRecordedAndTheNextSyncContinuesFromTheSameCursor() {
        sync(c02Event("E1", 2, null));
        provider.failure = new DomainException("POLICY_SOURCE_UNAVAILABLE", "down", true);
        assertThrows(DomainException.class, () -> ingest.sync(REVIEWER, "failed"));
        provider.failure = null;

        sync(c02Event("E2", 3, "v2"));

        assertThat(provider.cursors).containsExactly(null, "cursor-1", "cursor-1");
        assertThat(review.overview(REVIEWER).operations()).anyMatch(row -> PolicyChangeStore.SYNC_FAILED.equals(row.eventType()));
    }

    @Test void c02CandidatesAreOnlyOpenCasesWithTheNamedDelegationMethod() {
        sync(c02Event("E1", 2, null));

        var impact = review.detail(REVIEWER, onlyChange(C02)).impact().get(0);

        assertThat(impact.openCaseCount()).isEqualTo(3);
        assertThat(impact.candidates()).extracting(PolicyImpactResolver.CaseImpact::caseId).containsExactly(101L);
        assertThat(impact.candidates().get(0).reasonCodes()).containsExactly("DELEGATION_METHOD_MATCH");
        assertThat(impact.candidates().get(0).submittedBeforeEffective()).isTrue();
    }

    @Test void aChangeNamingADocumentSlotFindsCasesThatRequireIt() {
        ObjectNode event = event("E1", C02, 2, null);
        ((ObjectNode) event.path("detail").path("taskExtensions")).putObject(C02).putArray("documentTypeCodes").add("DECEDENT_BASIC_CERTIFICATE");

        sync(event);

        assertThat(review.detail(REVIEWER, onlyChange(C02)).impact().get(0).candidates()).extracting(PolicyImpactResolver.CaseImpact::caseId)
                .containsExactly(101L, 102L, 104L);
    }

    @Test void aWrittenDelegationChangeNeverTargetsReceiptCases() {
        ObjectNode event = event("E1", C02, 2, null);
        ((ObjectNode) event.path("detail").path("taskExtensions")).putObject(C02).putArray("delegationMethods").add("DOCUMENT");

        sync(event);

        assertThat(review.detail(REVIEWER, onlyChange(C02)).impact().get(0).candidates()).extracting(PolicyImpactResolver.CaseImpact::caseId).containsExactly(104L);
    }

    @Test void theAgentCannotWidenTheCandidateCases() throws Exception {
        sync(c02Event("E1", 2, null));
        agent.response = json.readTree("{\"summary\":\"ok\",\"recheckItems\":[{\"text\":\"t\",\"caseIds\":[101,102,104]}]}");

        JsonNode analysis = review.analyze(REVIEWER, onlyChange(C02), "a1");

        assertThat(analysis.path("recheckItems").get(0).path("caseIds")).hasSize(1);
        assertThat(analysis.path("droppedClaims").asInt()).isEqualTo(2);
    }

    @Test void anApprovedVersionSurvivesNewerOnesUntilOneIsApproved() {
        sync(c02Event("E1", 1, null));
        review.decide(REVIEWER, onlyChange(C02), PolicyChangeReviewService.Decision.NOTICE_APPROVED, null);
        sync(c02Event("E2", 2, "v1"));
        long v2 = onlyChange(C02);
        review.decide(REVIEWER, v2, PolicyChangeReviewService.Decision.NOTICE_APPROVED, null);
        sync(c02Event("E3", 3, "v2"));

        assertThat(review.notices(REVIEWER, C02, null).approved()).extracting(PolicyChangeReviewService.Notice::sourceVersion).containsExactly("v2");
        review.decide(REVIEWER, onlyChange(C02), PolicyChangeReviewService.Decision.NO_ACTION, "rejected");

        var notices = review.notices(REVIEWER, C02, null);
        assertThat(notices.approved()).extracting(PolicyChangeReviewService.Notice::sourceVersion).containsExactly("v2");
        assertThat(notices.pending()).isEmpty();
    }

    @Test void receivingAnalysingAndApprovingNeverTouchACaseOrRule() {
        String cases = caseFingerprint();
        String ruleData = ruleDataFingerprint();
        sync(c02Event("E1", 2, null));
        long id = onlyChange(C02);
        agent.response = json.createObjectNode().put("summary", "ok");

        review.analyze(REVIEWER, id, "a1");
        review.decide(REVIEWER, id, PolicyChangeReviewService.Decision.NOTICE_APPROVED, "note");

        assertThat(caseFingerprint()).isEqualTo(cases);
        assertThat(ruleDataFingerprint()).isEqualTo(ruleData).isNotNull();
    }

    @Test void aChangeReachesTheWorkflowNoticeOnlyAfterApproval() {
        sync(c02Event("E1", 2, null));
        long id = onlyChange(C02);

        var before = review.notices(REVIEWER, C02, null);
        assertThat(before.approved()).isEmpty();
        assertThat(before.pending()).extracting(PolicyChangeReviewService.Notice::id).containsExactly(id);

        review.decide(REVIEWER, id, PolicyChangeReviewService.Decision.NOTICE_APPROVED, null);

        var after = review.notices(REVIEWER, C02, null);
        assertThat(after.pending()).isEmpty();
        assertThat(after.approved()).extracting(PolicyChangeReviewService.Notice::effectiveState).containsExactly("SCHEDULED");
    }

    @Test void onlyTheNoticeDecisionPublishesANotice() {
        sync(c02Event("E1", 2, null));
        review.decide(REVIEWER, onlyChange(C02), PolicyChangeReviewService.Decision.DEV_REQUIRED, null);

        var notices = review.notices(REVIEWER, C02, null);
        assertThat(notices.approved()).isEmpty();
        assertThat(notices.pending()).isEmpty();
    }

    @Test void anUnreviewedNewerVersionDoesNotRemoveTheApprovedNotice() {
        sync(c02Event("E1", 2, null));
        review.decide(REVIEWER, onlyChange(C02), PolicyChangeReviewService.Decision.NOTICE_APPROVED, null);

        sync(c02Event("E2", 3, "v2"));

        var notices = review.notices(REVIEWER, C02, null);
        assertThat(notices.approved()).extracting(PolicyChangeReviewService.Notice::sourceVersion).containsExactly("v2");
        assertThat(notices.pending()).extracting(PolicyChangeReviewService.Notice::sourceVersion).containsExactly("v3");
    }

    @Test void aLateOlderVersionNeverBecomesTheNotice() {
        sync(c02Event("E3", 3, "v2"));
        review.decide(REVIEWER, onlyChange(C02), PolicyChangeReviewService.Decision.NOTICE_APPROVED, null);

        sync(c02Event("E2", 2, "v1"));

        var notices = review.notices(REVIEWER, C02, null);
        assertThat(notices.approved()).extracting(PolicyChangeReviewService.Notice::sourceVersion).containsExactly("v3");
        assertThat(notices.pending()).isEmpty();
    }

    @Test void anEffectiveBankWithdrawalEndsTheNotice() {
        sync(c02Event("E2", 2, null));
        review.decide(REVIEWER, onlyChange(C02), PolicyChangeReviewService.Decision.NOTICE_APPROVED, null);

        sync(c02Event("E3", 3, "v2").put("sourceStatus", "WITHDRAWN").put("effectiveAt", "2026-01-02T00:00:00Z"));

        var notices = review.notices(REVIEWER, C02, null);
        assertThat(notices.approved()).isEmpty();
        assertThat(notices.pending()).extracting(PolicyChangeReviewService.Notice::effectiveState).containsExactly("WITHDRAWN");
    }

    @Test void concurrentDifferentDecisionsLeaveExactlyOne() throws Exception {
        sync(c02Event("E1", 2, null));
        long id = onlyChange(C02);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<String>> calls = new ArrayList<>();
        for (var decision : List.of(PolicyChangeReviewService.Decision.NOTICE_APPROVED, PolicyChangeReviewService.Decision.NO_ACTION))
            calls.add(pool.submit(() -> {
                start.await();
                try { review.decide(REVIEWER, id, decision, null); return "OK"; }
                catch (DomainException exception) { return exception.code(); }
            }));
        start.countDown();
        List<String> results = new ArrayList<>();
        for (var call : calls) results.add(call.get());
        pool.shutdown();

        assertThat(results).containsExactlyInAnyOrder("OK", "POLICY_ALREADY_DECIDED");
        assertThat(jdbc.queryForObject("select count(*) from event_log where event_type=?", Integer.class, PolicyChangeStore.DECIDED)).isOne();
    }

    @Test void concurrentReceiptOfOneEventStoresOneRow() throws Exception {
        sync(c02Event("E1", 2, null));
        var stored = review.overview(REVIEWER).changes().get(0);
        jdbc.update("delete from event_log where event_type=?", PolicyChangeStore.RECEIVED);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<Boolean>> calls = new ArrayList<>();
        for (int index = 0; index < 4; index++)
            calls.add(pool.submit(() -> store.insertReceived(stored.change(), "hash", "ACCEPTED", false, List.of(C02), List.of())));
        int inserted = 0;
        for (var call : calls) if (call.get()) inserted++;
        pool.shutdown();

        assertThat(inserted).isOne();
        assertThat(jdbc.queryForObject("select count(*) from event_log where event_type=?", Integer.class, PolicyChangeStore.RECEIVED)).isOne();
    }

    @Test void aCaseSeesOnlyChangesThatConcernItAndWhy() {
        sync(c02Event("E1", 2, null));
        long id = onlyChange(C02);
        review.decide(REVIEWER, id, PolicyChangeReviewService.Decision.NOTICE_APPROVED, null);

        assertThat(review.notices(REVIEWER, C02, 101L).approved()).singleElement()
                .satisfies(row -> assertThat(row.reasonCodes()).containsExactly("DELEGATION_METHOD_MATCH"));
        assertThat(review.notices(REVIEWER, C02, 102L).approved()).isEmpty();
        assertThat(review.notices(REVIEWER, C02, 103L).approved()).as("closed Case").isEmpty();
        assertThat(review.notices(REVIEWER, B02, 101L).approved()).as("other task").isEmpty();
    }

    @Test void anUnapprovedChangeStaysSeparateOnTheCaseToo() {
        sync(c02Event("E1", 2, null));

        var notices = review.notices(REVIEWER, C02, 101L);

        assertThat(notices.approved()).isEmpty();
        assertThat(notices.pending()).hasSize(1);
    }

    @Test void aDecisionIsSingleAndAReplayIsSafe() {
        sync(c02Event("E1", 2, null));
        long id = onlyChange(C02);

        var first = review.decide(REVIEWER, id, PolicyChangeReviewService.Decision.NOTICE_APPROVED, "first");
        var replay = review.decide(REVIEWER, id, PolicyChangeReviewService.Decision.NOTICE_APPROVED, "second");

        assertThat(replay.note()).isEqualTo(first.note());
        assertThat(assertThrows(DomainException.class, () -> review.decide(REVIEWER, id, PolicyChangeReviewService.Decision.NO_ACTION, null)).code())
                .isEqualTo("POLICY_ALREADY_DECIDED");
        assertThat(jdbc.queryForObject("select count(*) from event_log where event_type=?", Integer.class, PolicyChangeStore.DECIDED)).isOne();
    }

    @Test void staffWithoutTheGrantCannotUseAnyPath() {
        sync(c02Event("E1", 2, null));
        long id = onlyChange(C02);

        for (var call : List.<org.junit.jupiter.api.function.Executable>of(() -> review.overview(NO_GRANT), () -> review.detail(NO_GRANT, id),
                () -> review.notices(NO_GRANT, C02, null), () -> review.analyze(NO_GRANT, id, "a"),
                () -> review.decide(NO_GRANT, id, PolicyChangeReviewService.Decision.NO_ACTION, null)))
            assertThat(assertThrows(DomainException.class, call).code()).isEqualTo("FORBIDDEN");
    }

    @Test void agentFailureLeavesTheChangeReviewableAndRecordsTheFailure() {
        sync(c02Event("E1", 2, null));
        long id = onlyChange(C02);
        agent.failure = new DomainException("STAFF_AGENT_UNAVAILABLE", "down", true);

        JsonNode result = review.analyze(REVIEWER, id, "a1");

        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(review.detail(REVIEWER, id).analysis().path("code").asText()).isEqualTo("STAFF_AGENT_UNAVAILABLE");
        assertThat(review.decide(REVIEWER, id, PolicyChangeReviewService.Decision.DEV_REQUIRED, null).decision()).isEqualTo("DEV_REQUIRED");
    }

    @Test void agentClaimsOutsideTheFactsAreRemovedAndSeparatedFromBankDesignation() throws Exception {
        sync(c02Event("E1", 2, null));
        long id = onlyChange(C02);
        agent.response = json.readTree("""
                {"summary":"ok","relatedTasks":[{"taskCode":"INHERITANCE_DEPOSIT","reason":"r"},{"taskCode":"DEBT_ADJUSTMENT","reason":"r"},
                {"taskCode":"MADE_UP","reason":"r"}],"recheckItems":[{"text":"t","caseIds":[101,103,999]}]}
                """);

        JsonNode analysis = review.analyze(REVIEWER, id, "a1");

        assertThat(analysis.path("relatedTasks")).hasSize(2);
        assertThat(analysis.path("relatedTasks").get(0).path("bankDesignated").asBoolean()).isTrue();
        assertThat(analysis.path("relatedTasks").get(1).path("bankDesignated").asBoolean()).isFalse();
        assertThat(analysis.path("recheckItems").get(0).path("caseIds")).hasSize(1);
        assertThat(analysis.path("droppedClaims").asInt()).isEqualTo(3);
    }

    @Test void theAgentReceivesNoCustomerData() {
        sync(c02Event("E1", 2, null));
        agent.response = json.createObjectNode().put("summary", "ok");

        review.analyze(REVIEWER, onlyChange(C02), "a1");

        String sent = json.valueToTree(agent.lastRequest).toString();
        assertThat(sent).contains("\"caseId\":101").doesNotContain("customer", "\"name\"", "heir");
    }

    @Test void aSecondExistingTaskUsesTheSamePathWithNoTaskCode() {
        var result = sync(event("B1", B02, 2, "v1"));

        assertThat(result.accepted()).isOne();
        long id = onlyChange(B02);
        assertThat(review.detail(REVIEWER, id).impact()).extracting(PolicyImpactResolver.TaskImpact::taskCode).containsExactly(B02);
        review.decide(REVIEWER, id, PolicyChangeReviewService.Decision.NOTICE_APPROVED, null);
        assertThat(review.notices(REVIEWER, B02, null).approved()).hasSize(1);
        assertThat(review.notices(REVIEWER, C02, null).approved()).isEmpty();
    }

    @Test void aNewTaskNeedsOnlyItsProfileAndABankWithAnotherFormatNeedsOnlyItsProvider() throws Exception {
        assertThat(registry.registered(NEW_TASK)).isTrue();
        JsonNode nativeEvent = json.readTree("""
                {"msg":"X-1","rule":"FX-LIMIT","rev":4,"prev":"r3","kind":"PROCEDURE_CHANGE","task":"FX_REMITTANCE",
                 "from":"2030-01-01T00:00:00Z","title":"[교육용 가상 사례] 송금 절차 변경",
                 "detail":{"before":"a","after":"b","taskExtensions":{"FX_REMITTANCE":{"currency":"USD"}}}}
                """);

        assertThat(sync(nativeEvent).accepted()).isOne();

        long id = onlyChange(NEW_TASK);
        var detail = review.detail(REVIEWER, id);
        assertThat(detail.view().ruleChangeReviewRequired()).isTrue();
        assertThat(detail.view().effectiveState()).isEqualTo("SCHEDULED");
        assertThat(review.decide(REVIEWER, id, PolicyChangeReviewService.Decision.DEV_REQUIRED, "code change").decision()).isEqualTo("DEV_REQUIRED");
    }

    @Test void aNewTaskProfileCanRejectItsOwnExtension() {
        ObjectNode event = event("X2", NEW_TASK, 1, null);
        ((ObjectNode) event.path("detail").path("taskExtensions")).putObject(NEW_TASK).put("currency", 840);

        assertThat(sync(event).quarantined()).isOne();
    }
}
