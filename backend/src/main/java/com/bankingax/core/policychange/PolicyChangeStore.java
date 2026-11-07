package com.bankingax.core.policychange;

import com.bankingax.core.persistence.ConditionalOnDataSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Append-only change register on the existing event_log, so no table is added (AGENTS.md section 4). Its UNIQUE
 * request_id gives idempotent receipt and one decision per change; rows are never updated, which keeps the audit trail.
 */
@Repository
@ConditionalOnDataSource
public class PolicyChangeStore {
    static final String RECEIVED = "POLICY_CHANGE_RECEIVED", QUARANTINED = "POLICY_CHANGE_QUARANTINED",
            SYNC_COMPLETED = "POLICY_SYNC_COMPLETED", SYNC_FAILED = "POLICY_SYNC_FAILED",
            ANALYZED = "POLICY_AGENT_ANALYZED", ANALYSIS_FAILED = "POLICY_AGENT_ANALYSIS_FAILED",
            DECIDED = "POLICY_REVIEW_DECIDED";
    private static final String CHANGE = "POLICY_CHANGE", QUARANTINE = "POLICY_CHANGE_QUARANTINE",
            SOURCE = "POLICY_SOURCE", REVIEW = "POLICY_CHANGE_REVIEW";
    private static final String COLUMNS = "select id,event_type,to_value,actor_type,actor_id,payload,created_at from event_log ";
    private static final int QUARANTINE_RAW_LIMIT = 4000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PolicyChangeStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** False when another receipt of this event already stored it. */
    boolean insertReceived(PolicyChange change, String contentHash, String intakeStatus, boolean versionGap,
                           List<String> mapped, List<String> unmapped) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("change", change);
        payload.put("contentHash", contentHash);
        payload.put("intakeStatus", intakeStatus);
        payload.put("versionGap", versionGap);
        payload.put("mappedTaskCodes", mapped);
        payload.put("unmappedTaskCodes", unmapped);
        payload.put("receivedAt", Instant.now());
        return append(CHANGE, policyKey(change.sourceSystem(), change.externalPolicyId()), RECEIVED,
                change.previousVersion(), change.sourceVersion(), "SYSTEM", null,
                eventKey(change.sourceSystem(), change.changeEventId()), payload);
    }

    void insertQuarantined(String source, JsonNode raw, List<String> problems) {
        String body = raw.toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("problems", problems);
        payload.put("raw", body.length() > QUARANTINE_RAW_LIMIT ? body.substring(0, QUARANTINE_RAW_LIMIT) : body);
        payload.put("changeEventId", raw.path("changeEventId").asText(null));
        append(QUARANTINE, source, QUARANTINED, null, String.join(",", problems), "SYSTEM", null,
                "policy-q:" + sha256(body), payload);
    }

    void insertSync(String source, String eventType, String cursor, long staffId, String requestId, Object payload) {
        append(SOURCE, source, eventType, null, cursor, "STAFF", staffId,
                "policy-sync:" + sha256(source + "|" + requestId + "|" + eventType), payload);
    }

    void insertAnalysis(long changeId, boolean succeeded, String requestId, Object payload) {
        append(REVIEW, Long.toString(changeId), succeeded ? ANALYZED : ANALYSIS_FAILED, null, null, "AGENT", null,
                "policy-analysis:" + sha256(changeId + "|" + requestId), payload);
    }

    /** False when this change already has a decision: the UNIQUE key keeps exactly one. */
    boolean insertDecision(long changeId, String decision, long staffId, Object payload) {
        return append(REVIEW, Long.toString(changeId), DECIDED, null, decision, "STAFF", staffId,
                "policy-review:" + changeId, payload);
    }

    Optional<Received> findByEvent(String source, String changeEventId) {
        return jdbc.query(COLUMNS + "where request_id=?", (rs, row) -> received(rs), eventKey(source, changeEventId))
                .stream().findFirst();
    }

    Optional<Received> find(long id) {
        return jdbc.query(COLUMNS + "where id=? and entity_type=? and event_type=?", (rs, row) -> received(rs), id, CHANGE, RECEIVED)
                .stream().findFirst();
    }

    List<Received> history(String source, String externalPolicyId) {
        return jdbc.query(COLUMNS + "where entity_type=? and entity_id=? and event_type=? order by id", (rs, row) -> received(rs),
                CHANGE, policyKey(source, externalPolicyId), RECEIVED);
    }

    List<Received> recent(int limit) {
        return jdbc.query(COLUMNS + "where event_type=? order by id desc limit ?", (rs, row) -> received(rs), RECEIVED, limit);
    }

    List<Entry> reviewEvents(long changeId) {
        return jdbc.query(COLUMNS + "where entity_type=? and entity_id=? order by id", (rs, row) -> entry(rs), REVIEW, Long.toString(changeId));
    }

    Map<Long, Entry> decisions() {
        Map<Long, Entry> rows = new LinkedHashMap<>();
        jdbc.query("select entity_id," + COLUMNS.substring("select ".length()) + "where event_type=?", rs -> {
            rows.put(Long.parseLong(rs.getString(1)), new Entry(rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getObject(6, Long.class), read(rs.getString(7)), instant(rs.getObject(8, LocalDateTime.class))));
        }, DECIDED);
        return rows;
    }

    List<Entry> recentOperations(int limit) {
        return new ArrayList<>(jdbc.query(COLUMNS + "where entity_type in (?,?) order by id desc limit ?", (rs, row) -> entry(rs),
                QUARANTINE, SOURCE, limit));
    }

    Optional<String> lastCursor(String source) {
        return jdbc.query("select to_value from event_log where entity_type=? and entity_id=? and event_type=? order by id desc limit 1",
                (rs, row) -> rs.getString(1), SOURCE, source, SYNC_COMPLETED).stream().findFirst();
    }

    String contentHash(PolicyChange change) {
        try {
            return sha256(json.writeValueAsString(json.valueToTree(change)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Change could not be serialized", exception);
        }
    }

    private boolean append(String entityType, String entityId, String eventType, String from, String to, String actorType,
                           Long actorId, String requestId, Object payload) {
        // "on duplicate key update" would report 1 for a no-op through Connector/J, hiding that the row already existed.
        try {
            jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) "
                    + "values(?,?,?,?,?,?,?,?,cast(? as json))",
                    entityType, entityId, eventType, from, to, actorType, actorId, requestId, write(payload));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    private Received received(java.sql.ResultSet rs) throws java.sql.SQLException {
        JsonNode payload = read(rs.getString(6));
        try {
            return new Received(rs.getLong(1), json.treeToValue(payload.path("change"), PolicyChange.class),
                    payload.path("intakeStatus").asText(), payload.path("versionGap").asBoolean(),
                    strings(payload.path("mappedTaskCodes")), strings(payload.path("unmappedTaskCodes")),
                    payload.path("contentHash").asText(), Instant.parse(payload.path("receivedAt").asText()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored change is unreadable", exception);
        }
    }

    private Entry entry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, Long.class),
                read(rs.getString(6)), instant(rs.getObject(7, LocalDateTime.class)));
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }

    private static String policyKey(String source, String externalPolicyId) { return source + ":" + externalPolicyId; }

    private static String eventKey(String source, String changeEventId) {
        return "policy-rx:" + sha256(source + "|" + changeEventId);
    }

    private JsonNode read(String value) {
        try { return json.readTree(value == null ? "{}" : value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Stored payload is unreadable", exception); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Payload could not be serialized", exception); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record Received(long id, PolicyChange change, String intakeStatus, boolean versionGap, List<String> mappedTaskCodes,
                    List<String> unmappedTaskCodes, String contentHash, Instant receivedAt) { }

    public record Entry(long id, String eventType, String value, String actorType, Long actorId, JsonNode payload, Instant at) { }
}
