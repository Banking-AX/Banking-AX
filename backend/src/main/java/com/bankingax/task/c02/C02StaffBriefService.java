package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import com.bankingax.core.policychange.PolicyChangeReviewService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff-requested review opinion. It stores reference text beside the Case and has no path to a Case status,
 * a staff decision or a customer notification; those stay behind the existing staff decision API.
 */
@Service
@ConditionalOnDataSource
public class C02StaffBriefService {
    private static final String CHECK_TYPE = "STAFF_BRIEF";
    private static final String TASK_CODE = "INHERITANCE_DEPOSIT";
    private static final Set<String> METHODS = Set.of("LLM", "RULE_FALLBACK");
    private static final int DRAFT_LIMIT = 1000;
    private static final String HISTORY_ENTITY = "STAFF_BRIEF", HISTORY_EVENT = "C02_STAFF_BRIEF_GENERATED";

    private final C02IntakeService intake;
    private final StaffBriefPort agent;
    private final OcrPersistence ocr;
    private final ObjectProvider<PolicyChangeReviewService> policy;
    private final ObjectProvider<C02DocumentReviewService> documentReviews;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public C02StaffBriefService(C02IntakeService intake, StaffBriefPort agent, ObjectProvider<OcrPersistence> ocr, ObjectProvider<PolicyChangeReviewService> policy, ObjectProvider<C02DocumentReviewService> documentReviews, JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions) {
        this.tx = new TransactionTemplate(transactions);
        this.intake = intake;
        this.agent = agent;
        this.ocr = ocr.getIfAvailable();
        this.policy = policy;
        this.documentReviews = documentReviews;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Same grant as the pre-review inbox, because the opinion describes one customer's Case. */
    public void requireReviewer(long staffId) {
        Boolean allowed = jdbc.query("select can_pre_review from `user` where id=? and is_active=true", rs -> rs.next() ? rs.getBoolean(1) : null, staffId);
        if (!Boolean.TRUE.equals(allowed)) throw new DomainException("FORBIDDEN", "Case review is not granted to this staff account.");
    }

    public Optional<JsonNode> latest(long caseId) {
        int revision = intake.currentRevisionForStaff(caseId);
        return jdbc.query("select report from agent_review_run where case_id=? and revision=? and check_type=?", (rs, row) -> read(rs.getString(1)), caseId, revision, CHECK_TYPE)
                .stream().findFirst();
    }

    /** An Agent failure returns FAILED and keeps any earlier opinion; the rule-based precheck on the page is untouched. */
    public JsonNode generate(long caseId, int revision) {
        return generate(caseId, revision, null);
    }

    public JsonNode generate(long caseId, int revision, Long staffId) {
        C02IntakeService.StaffCaseOverview overview = intake.staffCaseOverview(caseId);
        if (overview.caseView().revision() != revision) throw new DomainException("VERSION_CONFLICT", "The Case changed; reload before asking again.", true);
        if (overview.finalDecision() != null) throw new DomainException("CASE_ALREADY_DECIDED", "This Case already has a final staff decision.");
        String status = overview.caseView().caseStatus().name();
        if (!Set.of("SUBMITTED", "IN_REVIEW").contains(status)) throw new DomainException("INVALID_STATE", "Only a submitted Case can be reviewed.");
        Map<String, Object> facts = C02StaffBriefFacts.of(overview, documentNames(), ocr == null ? List.of() : ocr.snapshot(caseId));
        List<PolicyChangeReviewService.Notice> notices = approvedNotices(caseId);
        C02StaffBriefFacts.addPolicy(facts, notices, caseId);
        C02DocumentReviewService reviews = documentReviews.getIfAvailable();
        if (reviews != null) C02StaffBriefFacts.addStaffChecks(facts, reviews.overview(caseId).documents(), documentNames());
        C02StaffBriefFacts.addClarifications(facts, jdbc.queryForList("select kind, status from clarification where case_id=? order by id", caseId));
        // Only the number: the customer's words stay with staff, who read the original.
        facts.put("customerStatementCount", jdbc.queryForObject("select count(*) from case_statement where case_id=? and expires_at > UTC_TIMESTAMP(6)", Integer.class, caseId));
        try {
            ObjectNode brief = guarded(agent.write(facts));
            brief.put("status", "SUCCEEDED").put("revision", revision).put("analyzedAt", Instant.now().toString());
            recordAppliedPolicies(brief, notices);
            tx.executeWithoutResult(transaction -> {
                store(caseId, revision, brief);
                appendHistory(caseId, staffId, brief);
            });
            return brief;
        } catch (DomainException exception) {
            return json.createObjectNode().put("status", "FAILED").put("code", exception.code()).put("revision", revision);
        }
    }

    /** Every opinion ever generated for the Case, oldest first. The agent_review_run row keeps only the latest per revision. */
    public List<JsonNode> history(long caseId) {
        intake.currentRevisionForStaff(caseId);
        return jdbc.query("select payload from event_log where entity_type=? and entity_id=? and event_type=? order by id",
                (rs, row) -> read(rs.getString(1)), HISTORY_ENTITY, String.valueOf(caseId), HISTORY_EVENT);
    }

    /** Taken from what Spring selected, not from Agent output, so the record says which approved versions were really supplied. */
    private void recordAppliedPolicies(ObjectNode brief, List<PolicyChangeReviewService.Notice> notices) {
        if (notices == null) { brief.put("approvedPoliciesUnavailable", true); return; }
        ArrayNode applied = json.createArrayNode();
        for (PolicyChangeReviewService.Notice notice : notices)
            applied.addObject().put("noticeId", notice.id()).put("sourceVersion", notice.sourceVersion()).put("effectiveState", notice.effectiveState()).put("effectiveAt", String.valueOf(notice.effectiveAt()));
        brief.set("approvedPolicies", applied);
    }

    /** Append-only on event_log, so no table or column is added. The opinion holds no names or customer statements. */
    private void appendHistory(long caseId, Long staffId, ObjectNode brief) {
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) values(?,?,?,?,?,?,?,?,cast(? as json))",
                HISTORY_ENTITY, String.valueOf(caseId), HISTORY_EVENT, brief.path("promptVersion").asText(null), brief.path("method").asText(null),
                staffId == null ? "SYSTEM" : "STAFF", staffId, "staff-brief-" + UUID.randomUUID(), brief.toString());
    }

    /** Empty means none apply; null means they could not be read, which the opinion must say rather than imply there are none. */
    private List<PolicyChangeReviewService.Notice> approvedNotices(long caseId) {
        try {
            return policy.getObject().noticesFor(TASK_CODE, caseId).approved();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private ObjectNode guarded(JsonNode raw) {
        if (raw == null || !raw.isObject() || !METHODS.contains(raw.path("method").asText()) || raw.path("summary").asText("").isBlank()
                || raw.path("customerDraft").asText("").isBlank() || raw.path("customerDraft").asText().length() > DRAFT_LIMIT)
            throw new DomainException("STAFF_AGENT_RESPONSE_INVALID", "Staff Agent returned no usable opinion.", true);
        ObjectNode brief = json.createObjectNode();
        brief.put("method", raw.path("method").asText()).put("model", raw.path("model").asText(null)).put("promptVersion", raw.path("promptVersion").asText(null))
                .put("summary", raw.path("summary").asText()).put("customerDraft", raw.path("customerDraft").asText()).put("basisMissing", raw.path("basisMissing").asBoolean());
        brief.set("confirmed", strings(raw.path("confirmed")));
        brief.set("staffChecks", strings(raw.path("staffChecks")));
        Set<String> retrieved = new HashSet<>();
        ArrayNode evidence = json.createArrayNode();
        for (JsonNode row : raw.path("evidence")) { retrieved.add(row.path("docId").asText()); evidence.add(row); }
        ArrayNode basis = json.createArrayNode();
        for (JsonNode row : raw.path("basis")) if (retrieved.contains(row.path("docId").asText())) basis.add(row);
        brief.set("evidence", evidence);
        brief.set("basis", basis);
        return brief;
    }

    private ArrayNode strings(JsonNode values) {
        ArrayNode out = json.createArrayNode();
        for (JsonNode value : values) if (value.isTextual() && !value.asText().isBlank()) out.add(value.asText());
        return out;
    }

    private void store(long caseId, int revision, ObjectNode brief) {
        jdbc.update("""
                insert into agent_review_run(case_id,revision,check_type,status,attempt_count,model_version,prompt_version,report,started_at,finished_at)
                values(?,?,?,'SUCCEEDED',1,?,?,cast(? as json),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                on duplicate key update attempt_count=attempt_count+1,model_version=values(model_version),prompt_version=values(prompt_version),
                                        report=values(report),finished_at=UTC_TIMESTAMP(6)
                """, caseId, revision, CHECK_TYPE, brief.path("model").asText(null), brief.path("promptVersion").asText(null), brief.toString());
    }

    private Map<String, String> documentNames() {
        Map<String, String> names = new HashMap<>();
        jdbc.query("select code,name from document_type", rs -> { names.put(rs.getString(1), rs.getString(2)); });
        return names;
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) { throw new DomainException("STAFF_BRIEF_UNREADABLE", "The stored opinion could not be read.", true); }
    }
}
