package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAFF-06: a staff member records, per submitted file, that the original was checked or needs a supplement.
 *
 * <p>The decision belongs to one file version. History is append-only in event_log (entity DOCUMENT_FILE); the current
 * state is mirrored on required_document (status, human_checked_*), which the upload path resets when a new file arrives.
 * Counts only ever use the event of the slot's current file, so a new or voided file is never shown as checked.
 * Upload, OCR comparison and this staff check are separate facts and are reported separately.
 */
@Service
@ConditionalOnDataSource
public class C02DocumentReviewService {
    public static final String EVENT = "DOCUMENT_STAFF_REVIEWED";
    private static final Set<String> RESULTS = Set.of("CONFIRMED", "SUPPLEMENT_REQUIRED");
    private static final int NOTE_LIMIT = 255;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public C02DocumentReviewService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Review(long eventId, String result, String reason, Long staffId, String staffName, Instant reviewedAt) { }
    public record Row(long requiredDocumentId, String documentTypeCode, String requirement, String personRef, String personName,
                      Long documentFileId, Integer fileVersion, String fileName, Instant uploadedAt, String ocrMethod, String ocrStatus,
                      Review review) { }
    public record Summary(int submitted, int confirmed, int supplement, int unreviewed, Instant lastReviewedAt) { }
    public record Overview(long caseId, String caseStatus, Long reviewerId, String reviewerName, Instant reviewStartedAt,
                           boolean editable, List<Row> documents, List<Row> visitOnly, Summary summary) { }
    public record HistoryEntry(long eventId, long documentFileId, int fileVersion, String documentTypeCode, String personName,
                               String previousResult, String result, String reason, Long staffId, String staffName, Instant reviewedAt,
                               boolean currentFile) { }
    public record Command(int fileVersion, String result, String reason, Long expectedReviewId, String requestId) { }
    public record TimelineEntry(Instant at, String kind, String actorType, String actorName, String summary, String detail) { }

    /** Case events, staff document checks and Agent runs in time order. Nothing here is derived or reconstructed. */
    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(long caseId) {
        caseHead(caseId);
        List<TimelineEntry> out = new ArrayList<>();
        jdbc.query("""
            select e.created_at, e.event_type, e.from_value, e.to_value, e.actor_type, u.name,
                   json_unquote(json_extract(e.payload,'$.exceptionReason')), json_unquote(json_extract(e.payload,'$.reason'))
              from event_log e left join `user` u on u.id = e.actor_id
             where (e.entity_type = 'SERVICE_CASE' and e.entity_id = ?)
                or (e.entity_type = 'DOCUMENT_FILE' and json_extract(e.payload,'$.caseId') = ?)
            """, rs -> {
            String type = rs.getString(2);
            String exception = nullIfJsonNull(rs.getString(7));
            String reason = nullIfJsonNull(rs.getString(8));
            String detail = EVENT.equals(type) ? reason : exception == null ? null : "예외 사유: " + exception;
            out.add(new TimelineEntry(rs.getTimestamp(1).toInstant(), type, rs.getString(5), rs.getString(6),
                    (rs.getString(3) == null ? "" : rs.getString(3) + " → ") + (rs.getString(4) == null ? "" : rs.getString(4)), detail));
        }, String.valueOf(caseId), caseId);
        jdbc.query("select coalesce(finished_at, started_at), check_type, status, model_version, prompt_version, revision from agent_review_run where case_id=? and coalesce(finished_at, started_at) is not null",
                rs -> { out.add(new TimelineEntry(rs.getTimestamp(1).toInstant(), "AGENT_RUN:" + rs.getString(2), "AGENT", rs.getString(4),
                        rs.getString(3) + " · r" + rs.getInt(6), rs.getString(5))); }, caseId);
        out.sort(java.util.Comparator.comparing(TimelineEntry::at));
        return out;
    }

    /** Assigned reviewer = the staff member who started the review (staff_review.staff_id), never the viewer. */
    private record Reviewer(Long staffId, String name, Instant startedAt) { }

    @Transactional(readOnly = true)
    public Overview overview(long caseId) {
        Map<String, Object> head = caseHead(caseId);
        Reviewer reviewer = reviewer(caseId);
        List<Row> all = rows(caseId);
        List<Row> documents = all.stream().filter(row -> !"VISIT_ONLY".equals(row.requirement()) && (!"OPTIONAL".equals(row.requirement()) || row.documentFileId() != null)).toList();
        List<Row> visitOnly = all.stream().filter(row -> "VISIT_ONLY".equals(row.requirement())).toList();
        String status = (String) head.get("case_status");
        return new Overview(caseId, status, reviewer.staffId(), reviewer.name(), reviewer.startedAt(), "IN_REVIEW".equals(status),
                documents, visitOnly, summarize(documents));
    }

    static Summary summarize(List<Row> documents) {
        int submitted = 0, confirmed = 0, supplement = 0;
        Instant last = null;
        for (Row row : documents) {
            if (row.documentFileId() == null) continue;
            submitted++;
            if (row.review() == null) continue;
            if ("CONFIRMED".equals(row.review().result())) confirmed++;
            else supplement++;
            if (last == null || row.review().reviewedAt().isAfter(last)) last = row.review().reviewedAt();
        }
        return new Summary(submitted, confirmed, supplement, submitted - confirmed - supplement, last);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> history(long caseId) {
        caseHead(caseId);
        return jdbc.query("""
            select e.id, df.id, df.version, rd.document_type_code, h.name, e.from_value, e.to_value,
                   json_unquote(json_extract(e.payload,'$.reason')), e.actor_id, u.name, e.created_at,
                   (df.is_latest = true and df.deleted_at is null)
              from event_log e
              join document_file df on e.entity_id = cast(df.id as char)
              join required_document rd on rd.id = df.required_document_id
              join service_case sc on sc.work_item_id = rd.work_item_id
              left join heir h on h.id = rd.heir_id
              left join `user` u on u.id = e.actor_id
             where sc.id = ? and e.entity_type = 'DOCUMENT_FILE' and e.event_type = ?
             order by e.id desc
            """, (rs, n) -> new HistoryEntry(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), nullIfJsonNull(rs.getString(8)), (Long) rs.getObject(9, Long.class), rs.getString(10),
                rs.getTimestamp(11).toInstant(), rs.getBoolean(12)), caseId, EVENT);
    }

    /**
     * Records one decision on the slot's current file. Refuses a file that is no longer current, a stale version, a
     * concurrent decision made in between, a Case that is not under review, and a staff member who is not its reviewer.
     */
    @Transactional
    public Row review(long staffId, long caseId, long documentFileId, Command command) {
        String requestId = requireRequestId(command.requestId());
        String result = command.result() == null ? "" : command.result();
        if (!RESULTS.contains(result)) throw new DomainException("VALIDATION_ERROR", "result must be CONFIRMED or SUPPLEMENT_REQUIRED.");
        String reason = command.reason() == null ? null : command.reason().trim();
        if ("SUPPLEMENT_REQUIRED".equals(result) && (reason == null || reason.isEmpty()))
            throw new DomainException("VALIDATION_ERROR", "A supplement needs a reason the customer can act on.");
        if (reason != null && reason.length() > NOTE_LIMIT) throw new DomainException("VALIDATION_ERROR", "The reason is limited to 255 characters.");
        if (reason != null && reason.isEmpty()) reason = null;

        List<Map<String, Object>> replay = jdbc.queryForList("select entity_id, to_value from event_log where request_id=?", requestId);
        if (!replay.isEmpty()) {
            if (String.valueOf(documentFileId).equals(replay.get(0).get("entity_id")) && result.equals(replay.get(0).get("to_value")))
                return row(caseId, documentFileId);
            throw new DomainException("DUPLICATE_REQUEST_CONFLICT", "requestId was reused for another document review.");
        }

        List<Map<String, Object>> locked = jdbc.queryForList("select case_status, case_type from service_case where id=? for update", caseId);
        if (locked.isEmpty() || !"INHERITANCE_DEPOSIT".equals(locked.get(0).get("case_type"))) throw new DomainException("NOT_FOUND", "C-02 case was not found.");
        String status = (String) locked.get(0).get("case_status");
        if (status.startsWith("REVIEW_") || "CANCELED".equals(status)) throw new DomainException("CASE_ALREADY_DECIDED", "This Case is closed; document checks can no longer change.");
        if (!"IN_REVIEW".equals(status)) throw new DomainException("REVIEW_NOT_STARTED", "Start the review before recording document checks.");
        Reviewer reviewer = reviewer(caseId);
        if (reviewer.staffId() == null || reviewer.staffId() != staffId)
            throw new DomainException("FORBIDDEN", "Only the staff member who started this review can record document checks.");

        List<Map<String, Object>> files = jdbc.queryForList("""
            select df.id, df.version, df.is_latest, df.deleted_at, rd.id as required_id, rd.requirement
              from document_file df join required_document rd on rd.id = df.required_document_id
              join service_case sc on sc.work_item_id = rd.work_item_id
             where df.id = ? and sc.id = ? for update
            """, documentFileId, caseId);
        if (files.isEmpty()) throw new DomainException("NOT_FOUND", "The file does not belong to this Case.");
        Map<String, Object> file = files.get(0);
        if ("VISIT_ONLY".equals(file.get("requirement"))) throw new DomainException("VALIDATION_ERROR", "Visit-only documents are checked at the branch.");
        if (!Boolean.TRUE.equals(asBoolean(file.get("is_latest"))) || file.get("deleted_at") != null)
            throw new DomainException("DOCUMENT_STALE", "A newer file replaced this one; reload and check the current file.", true);
        if (((Number) file.get("version")).intValue() != command.fileVersion())
            throw new DomainException("DOCUMENT_STALE", "The file version changed; reload and check the current file.", true);

        Review current = latestReview(documentFileId);
        long expected = command.expectedReviewId() == null ? 0 : command.expectedReviewId();
        if ((current == null ? 0 : current.eventId()) != expected)
            throw new DomainException("REVIEW_CONFLICT", "Another decision was saved for this file; reload to see it.", true);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", caseId);
        payload.put("requiredDocumentId", ((Number) file.get("required_id")).longValue());
        payload.put("documentFileId", documentFileId);
        payload.put("fileVersion", command.fileVersion());
        payload.put("reason", reason);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) values('DOCUMENT_FILE',?,?,?,?,'STAFF',?,?,cast(? as json))",
                String.valueOf(documentFileId), EVENT, current == null ? "NOT_REVIEWED" : current.result(), result, staffId, requestId, toJson(payload));
        jdbc.update("update required_document set status=?, human_checked_by=?, human_checked_at=UTC_TIMESTAMP(6), human_check_note=? where id=?",
                "CONFIRMED".equals(result) ? "HUMAN_VERIFIED" : "NEEDS_SUPPLEMENT", staffId, reason, ((Number) file.get("required_id")).longValue());
        return row(caseId, documentFileId);
    }

    /** Current-file decisions for every C-02 Case in one query, for the queue. */
    @Transactional(readOnly = true)
    public Map<Long, Summary> summaries(List<Long> caseIds) {
        Map<Long, List<Row>> byCase = new LinkedHashMap<>();
        if (caseIds.isEmpty()) return Map.of();
        String in = String.join(",", caseIds.stream().map(id -> "?").toList());
        jdbc.query(ROWS + " where sc.id in (" + in + ") and rd.requirement <> 'VISIT_ONLY' and df.id is not null", rs -> {
            byCase.computeIfAbsent(rs.getLong("case_id"), key -> new ArrayList<>()).add(map(rs));
        }, caseIds.toArray());
        Map<Long, Summary> out = new LinkedHashMap<>();
        for (Long id : caseIds) out.put(id, summarize(byCase.getOrDefault(id, List.of())));
        return out;
    }

    private static final String ROWS = """
        select sc.id as case_id, rd.id as required_id, rd.document_type_code, rd.requirement, rd.condition_note, h.person_ref, h.name as person_name,
               df.id as file_id, df.version, df.original_name, df.uploaded_at,
               (select de.method from document_extraction de where de.document_file_id = df.id order by de.id desc limit 1) as ocr_method,
               (select de.status from document_extraction de where de.document_file_id = df.id order by de.id desc limit 1) as ocr_status,
               (select e.id from event_log e where e.entity_type='DOCUMENT_FILE' and e.entity_id = cast(df.id as char) and e.event_type='DOCUMENT_STAFF_REVIEWED' order by e.id desc limit 1) as review_id
          from service_case sc
          join required_document rd on rd.work_item_id = sc.work_item_id
          left join heir h on h.id = rd.heir_id
          left join document_file df on df.required_document_id = rd.id and df.is_latest = true and df.deleted_at is null
        """;

    private List<Row> rows(long caseId) {
        return jdbc.query(ROWS + " where sc.id = ? and coalesce(rd.condition_note,'') <> 'RETIRED_BY_DRAFT_EDIT' order by rd.id", (rs, n) -> map(rs), caseId);
    }

    private Row row(long caseId, long documentFileId) {
        return jdbc.query(ROWS + " where sc.id = ? and df.id = ?", (rs, n) -> map(rs), caseId, documentFileId).stream().findFirst()
                .orElseThrow(() -> new DomainException("NOT_FOUND", "The file does not belong to this Case."));
    }

    private Row map(ResultSet rs) throws SQLException {
        Long fileId = (Long) rs.getObject("file_id", Long.class);
        Long reviewId = (Long) rs.getObject("review_id", Long.class);
        Timestamp uploaded = rs.getTimestamp("uploaded_at");
        return new Row(rs.getLong("required_id"), rs.getString("document_type_code"), rs.getString("requirement"), rs.getString("person_ref"),
                rs.getString("person_name"), fileId, fileId == null ? null : rs.getInt("version"), rs.getString("original_name"),
                uploaded == null ? null : uploaded.toInstant(), rs.getString("ocr_method"), rs.getString("ocr_status"),
                reviewId == null ? null : reviewById(reviewId));
    }

    private Review latestReview(long documentFileId) {
        return jdbc.query("select id from event_log where entity_type='DOCUMENT_FILE' and entity_id=? and event_type=? order by id desc limit 1",
                (rs, n) -> rs.getLong(1), String.valueOf(documentFileId), EVENT).stream().findFirst().map(this::reviewById).orElse(null);
    }

    private Review reviewById(long eventId) {
        return jdbc.queryForObject("""
            select e.id, e.to_value, json_unquote(json_extract(e.payload,'$.reason')), e.actor_id, u.name, e.created_at
              from event_log e left join `user` u on u.id = e.actor_id where e.id = ?
            """, (rs, n) -> new Review(rs.getLong(1), rs.getString(2), nullIfJsonNull(rs.getString(3)), (Long) rs.getObject(4, Long.class),
                rs.getString(5), rs.getTimestamp(6).toInstant()), eventId);
    }

    private Reviewer reviewer(long caseId) {
        return jdbc.query("select sr.staff_id, u.name, sr.started_at from staff_review sr left join `user` u on u.id = sr.staff_id where sr.case_id=? order by sr.id desc limit 1",
                (rs, n) -> new Reviewer(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()), caseId).stream().findFirst()
                .orElse(new Reviewer(null, null, null));
    }

    private Map<String, Object> caseHead(long caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select case_status from service_case where id=? and case_type='INHERITANCE_DEPOSIT'", caseId);
        if (rows.isEmpty()) throw new DomainException("NOT_FOUND", "C-02 case was not found.");
        return rows.get(0);
    }

    private static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 90) throw new DomainException("VALIDATION_ERROR", "requestId is required (max 90).");
        return requestId;
    }

    private static Boolean asBoolean(Object value) {
        return value instanceof Boolean flag ? flag : value instanceof Number number ? number.intValue() != 0 : null;
    }

    private static String nullIfJsonNull(String value) {
        return value == null || "null".equals(value) ? null : value;
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new DomainException("INTERNAL_ERROR", "Review payload could not be written.", true); }
    }
}
