package com.bankingax.core.policychange;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Staff view of the register. The bank's status (sourceStatus, effectiveState) and Banking-AX's own review state are
 * separate fields, and a decision only ever affects the staff notice: no path here reaches a rule or a Case.
 */
@Service
@ConditionalOnDataSource
public class PolicyChangeReviewService {
    private static final int LIST_LIMIT = 300, OPERATION_LIMIT = 30, NOTE_LIMIT = 500;

    public enum Decision { NO_ACTION, NOTICE_APPROVED, DEV_REQUIRED }

    private final PolicyChangeStore store;
    private final PolicyImpactResolver impacts;
    private final PolicyAgentPort agent;
    private final PolicyTaskRegistry tasks;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PolicyChangeReviewService(PolicyChangeStore store, PolicyImpactResolver impacts, PolicyAgentPort agent,
                                     PolicyTaskRegistry tasks, JdbcTemplate jdbc, ObjectMapper json) {
        this.store = store;
        this.impacts = impacts;
        this.agent = agent;
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Candidate lists name customer Cases, so this uses the same grant as the pre-review inbox. */
    public void requireReviewer(long staffId) {
        Boolean allowed = jdbc.query("select can_pre_review from `user` where id=? and is_active=true",
                rs -> rs.next() ? rs.getBoolean(1) : null, staffId);
        if (!Boolean.TRUE.equals(allowed)) throw new DomainException("FORBIDDEN", "Change review is not granted to this staff account.");
    }

    public Overview overview(long staffId) {
        requireReviewer(staffId);
        List<PolicyChangeStore.Received> rows = store.recent(LIST_LIMIT);
        Map<Long, PolicyChangeStore.Entry> decisions = store.decisions();
        return new Overview(rows.stream().map(row -> view(row, rows, decisions.get(row.id()))).toList(),
                store.recentOperations(OPERATION_LIMIT));
    }

    public Detail detail(long staffId, long changeId) {
        requireReviewer(staffId);
        PolicyChangeStore.Received received = required(changeId);
        List<PolicyChangeStore.Received> history = store.history(received.change().sourceSystem(), received.change().externalPolicyId());
        List<PolicyChangeStore.Entry> events = store.reviewEvents(changeId);
        PolicyChangeStore.Entry decision = events.stream().filter(row -> PolicyChangeStore.DECIDED.equals(row.eventType())).findFirst().orElse(null);
        JsonNode analysis = events.stream().filter(row -> !PolicyChangeStore.DECIDED.equals(row.eventType()))
                .reduce((first, second) -> second).map(PolicyChangeStore.Entry::payload).orElse(null);
        return new Detail(view(received, history, decision), history.stream().map(PolicyChangeReviewService::version).toList(),
                impacts.resolve(received, history), analysis, events, tasks.taskCodes());
    }

    /** Runs the Agent. A failure is recorded and returned: the received change is untouched and can be retried. */
    public JsonNode analyze(long staffId, long changeId, String requestId) {
        requireReviewer(staffId);
        PolicyChangeStore.Received received = reviewable(changeId);
        List<PolicyChangeStore.Received> history = store.history(received.change().sourceSystem(), received.change().externalPolicyId());
        List<PolicyImpactResolver.TaskImpact> impact = impacts.resolve(received, history);
        List<Map<String, Object>> candidates = new ArrayList<>();
        impact.forEach(task -> task.candidates().forEach(row -> candidates.add(Map.of("caseId", row.caseId(), "revision", row.revision(),
                "caseStatus", row.caseStatus(), "taskCode", task.taskCode(), "reasonCodes", row.reasonCodes()))));
        try {
            ObjectNode analysis = guarded(agent.analyze(Map.of("change", json.valueToTree(received.change()), "mappedTaskCodes", received.mappedTaskCodes(),
                    "registeredTasks", tasks.taskCodes(), "caseCandidates", candidates)), received, candidates);
            analysis.put("status", "SUCCEEDED").put("analyzedAt", Instant.now().toString());
            store.insertAnalysis(changeId, true, requestId, analysis);
            return analysis;
        } catch (DomainException exception) {
            ObjectNode failure = json.createObjectNode().put("status", "FAILED").put("code", exception.code())
                    .put("analyzedAt", Instant.now().toString());
            store.insertAnalysis(changeId, false, requestId, failure);
            return failure;
        }
    }

    /** One decision per change, enforced by the UNIQUE key; repeating the same decision is a safe replay. */
    public DecisionView decide(long staffId, long changeId, Decision decision, String note) {
        requireReviewer(staffId);
        if (decision == null) throw new DomainException("POLICY_DECISION_REQUIRED", "A review decision is required.");
        if (note != null && note.length() > NOTE_LIMIT) throw new DomainException("POLICY_NOTE_TOO_LONG", "The review note is too long.");
        Optional<PolicyChangeStore.Entry> existing = decisionOf(changeId);
        if (existing.isEmpty()) {
            reviewable(changeId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("note", note);
            store.insertDecision(changeId, decision.name(), staffId, payload);
            existing = decisionOf(changeId);
        }
        PolicyChangeStore.Entry stored = existing.orElseThrow();
        if (!decision.name().equals(stored.value()))
            throw new DomainException("POLICY_ALREADY_DECIDED", "This change already has a different review decision.");
        return decisionView(stored);
    }

    /**
     * The only read a Workflow screen uses. Nothing here is a rule; unreviewed changes are labelled as not adopted.
     * With a caseId, only changes that concern that open Case are returned, with the reasons they do.
     */
    public Notices notices(long staffId, String taskCode, Long caseId) {
        requireReviewer(staffId);
        return noticesFor(taskCode, caseId);
    }

    /** For callers that have already checked the staff grant. */
    public Notices noticesFor(String taskCode, Long caseId) {
        Map<Long, PolicyChangeStore.Received> byId = new LinkedHashMap<>();
        Map<String, List<PolicyChangeStore.Received>> policies = new LinkedHashMap<>();
        for (PolicyChangeStore.Received row : store.recent(LIST_LIMIT)) {
            if (!PolicyChangeIngestService.ACCEPTED.equals(row.intakeStatus()) || !row.mappedTaskCodes().contains(taskCode)) continue;
            byId.put(row.id(), row);
            policies.computeIfAbsent(row.change().sourceSystem() + ":" + row.change().externalPolicyId(), key -> new ArrayList<>()).add(row);
        }
        Map<Long, PolicyChangeStore.Entry> decisions = store.decisions();
        Instant now = Instant.now();
        List<Notice> approved = new ArrayList<>(), pending = new ArrayList<>();
        for (List<PolicyChangeStore.Received> versions : policies.values()) {
            var selection = PolicyNoticeSelector.select(versions.stream().map(row -> version(row, decisions.get(row.id()))).toList(), now);
            for (var shown : selection.approved())
                noticeFor(byId.get(shown.version().id()), shown.newerBankVersion(), taskCode, caseId).ifPresent(approved::add);
            selection.pending().flatMap(row -> noticeFor(byId.get(row.id()), null, taskCode, caseId)).ifPresent(pending::add);
        }
        return new Notices(taskCode, caseId, approved, pending);
    }

    private static PolicyNoticeSelector.Version version(PolicyChangeStore.Received row, PolicyChangeStore.Entry decision) {
        PolicyChange change = row.change();
        return new PolicyNoticeSelector.Version(row.id(), change.versionOrder(), change.sourceVersion(), change.sourceStatus(),
                change.effectiveAt(), decision == null ? null : decision.value());
    }

    private Optional<Notice> noticeFor(PolicyChangeStore.Received row, String newerBankVersion, String taskCode, Long caseId) {
        List<String> reasons = caseId == null ? List.of() : impacts.reasonsFor(row.change(), taskCode, caseId);
        if (caseId != null && reasons.isEmpty()) return Optional.empty();
        PolicyChange change = row.change();
        return Optional.of(new Notice(row.id(), change.sourceVersion(), change.changeSummary(), effectiveState(change),
                change.effectiveAt(), change.changeType().ruleChangeReviewRequired(), newerBankVersion, reasons));
    }

    private ObjectNode guarded(JsonNode raw, PolicyChangeStore.Received received, List<Map<String, Object>> candidates) {
        if (raw == null || !raw.isObject() || raw.path("summary").asText("").isBlank())
            throw new DomainException("STAFF_AGENT_RESPONSE_INVALID", "Staff Agent returned no usable analysis.", true);
        ObjectNode analysis = raw.deepCopy();
        Set<Long> caseIds = candidates.stream().map(row -> (Long) row.get("caseId")).collect(Collectors.toSet());
        int dropped = 0;
        ArrayNode related = json.createArrayNode();
        for (JsonNode task : analysis.withArray("relatedTasks")) {
            String code = task.path("taskCode").asText();
            if (!tasks.registered(code)) { dropped++; continue; }
            related.add(task.deepCopy().<ObjectNode>require().put("bankDesignated", received.mappedTaskCodes().contains(code)));
        }
        analysis.set("relatedTasks", related);
        ArrayNode recheck = json.createArrayNode();
        for (JsonNode item : analysis.withArray("recheckItems")) {
            ArrayNode kept = json.createArrayNode();
            for (JsonNode id : item.path("caseIds")) {
                if (caseIds.contains(id.asLong())) kept.add(id); else dropped++;
            }
            recheck.add(item.deepCopy().<ObjectNode>require().set("caseIds", kept));
        }
        analysis.set("recheckItems", recheck);
        return analysis.put("droppedClaims", dropped);
    }

    private PolicyChangeStore.Received required(long changeId) {
        return store.find(changeId).orElseThrow(() -> new DomainException("NOT_FOUND", "The change was not found."));
    }

    private PolicyChangeStore.Received reviewable(long changeId) {
        PolicyChangeStore.Received received = required(changeId);
        List<PolicyChangeStore.Received> history = store.history(received.change().sourceSystem(), received.change().externalPolicyId());
        if (!PolicyChangeIngestService.ACCEPTED.equals(received.intakeStatus()) || !isLatest(received, history))
            throw new DomainException("POLICY_CHANGE_NOT_REVIEWABLE", "Only the latest accepted version can be reviewed.");
        return received;
    }

    private Optional<PolicyChangeStore.Entry> decisionOf(long changeId) {
        return store.reviewEvents(changeId).stream().filter(row -> PolicyChangeStore.DECIDED.equals(row.eventType())).findFirst();
    }

    private ChangeView view(PolicyChangeStore.Received row, List<PolicyChangeStore.Received> peers, PolicyChangeStore.Entry decision) {
        PolicyChange change = row.change();
        return new ChangeView(row.id(), change, row.intakeStatus(), reviewStatus(row, peers, decision), effectiveState(change),
                change.changeType().ruleChangeReviewRequired(), row.versionGap(), row.mappedTaskCodes(), row.unmappedTaskCodes(),
                row.receivedAt(), decision == null ? null : decisionView(decision));
    }

    private static String reviewStatus(PolicyChangeStore.Received row, List<PolicyChangeStore.Received> peers, PolicyChangeStore.Entry decision) {
        if (PolicyChangeIngestService.OUT_OF_SCOPE.equals(row.intakeStatus())) return "NOT_APPLICABLE";
        if (PolicyChangeIngestService.STALE.equals(row.intakeStatus())) return "STALE_VERSION";
        if (decision != null) return decision.value();
        return isLatest(row, peers) ? "PENDING_REVIEW" : "SUPERSEDED";
    }

    private static boolean isLatest(PolicyChangeStore.Received row, List<PolicyChangeStore.Received> peers) {
        return PolicyChangeIngestService.ACCEPTED.equals(row.intakeStatus()) && peers.stream()
                .filter(peer -> PolicyChangeIngestService.ACCEPTED.equals(peer.intakeStatus()))
                .filter(peer -> Objects.equals(peer.change().externalPolicyId(), row.change().externalPolicyId())
                        && Objects.equals(peer.change().sourceSystem(), row.change().sourceSystem()))
                .max(Comparator.comparingLong(peer -> peer.change().versionOrder()))
                .map(peer -> peer.id() == row.id()).orElse(false);
    }

    private static String effectiveState(PolicyChange change) {
        if ("WITHDRAWN".equals(change.sourceStatus())) return "WITHDRAWN";
        return change.effectiveAt().isAfter(Instant.now()) ? "SCHEDULED" : "IN_EFFECT";
    }

    private static VersionRow version(PolicyChangeStore.Received row) {
        return new VersionRow(row.id(), row.change().sourceVersion(), row.change().versionOrder(), row.change().previousVersion(),
                row.intakeStatus(), row.versionGap(), row.change().effectiveAt(), row.receivedAt());
    }

    private static DecisionView decisionView(PolicyChangeStore.Entry entry) {
        return new DecisionView(entry.value(), entry.actorId(), entry.at(), entry.payload().path("note").asText(null));
    }

    public record Overview(List<ChangeView> changes, List<PolicyChangeStore.Entry> operations) { }
    public record ChangeView(long id, PolicyChange change, String intakeStatus, String reviewStatus, String effectiveState,
                             boolean ruleChangeReviewRequired, boolean versionGap, List<String> mappedTaskCodes,
                             List<String> unmappedTaskCodes, Instant receivedAt, DecisionView decision) { }
    public record Detail(ChangeView view, List<VersionRow> versions, List<PolicyImpactResolver.TaskImpact> impact,
                         JsonNode analysis, List<PolicyChangeStore.Entry> audit, Set<String> registeredTasks) { }
    public record VersionRow(long id, String sourceVersion, long versionOrder, String previousVersion, String intakeStatus,
                             boolean versionGap, Instant effectiveAt, Instant receivedAt) { }
    public record DecisionView(String decision, Long staffId, Instant decidedAt, String note) { }
    public record Notices(String taskCode, Long caseId, List<Notice> approved, List<Notice> pending) { }
    public record Notice(long id, String sourceVersion, String summary, String effectiveState, Instant effectiveAt,
                         boolean ruleChangeReviewRequired, String newerBankVersion, List<String> reasonCodes) { }
}
