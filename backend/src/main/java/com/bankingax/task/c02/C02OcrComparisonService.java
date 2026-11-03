package com.bankingax.task.c02;

import com.fasterxml.jackson.databind.JsonNode;
import com.bankingax.core.api.DomainException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Deterministic, non-legal comparison of OCR candidates against current C-02 Case rows. */
@Service
@ConditionalOnProperty(name = "app.c02.document-port", havingValue = "jpa")
public class C02OcrComparisonService {
    private final JdbcTemplate jdbc;
    private final double minimumConfidence;

    public C02OcrComparisonService(JdbcTemplate jdbc,
            @Value("${app.c02.ocr.comparison.minimum-confidence:0.90}") double minimumConfidence) {
        this.jdbc = jdbc;
        this.minimumConfidence = minimumConfidence >= 0 && minimumConfidence <= 1 ? minimumConfidence : 0.90;
    }

    public List<FieldComparison> compare(long caseId, int revision, OcrPersistence.OcrDocument document) {
        if (!ClovaTemplateOcrAdapter.ALLOWED_DOCUMENT_TYPE.equals(document.documentTypeCode())) return List.of();
        if (document.caseRevision() != revision) throw new DomainException("VERSION_CONFLICT", "OCR comparison requires the current document revision.", true);
        if ("NOT_APPLICABLE".equals(document.status())) return List.of();
        if (!"SUCCEEDED".equals(document.status())) return failedResults(document.status());

        Reference reference = jdbc.query("""
                select sc.revision, df.case_revision, df.is_latest, df.deleted_at,
                       rd.document_type_code, h.id as heir_id, h.case_id as heir_case_id,
                       h.person_ref as heir_person_ref, h.name as heir_name
                from document_file df
                join required_document rd on rd.id=df.required_document_id
                join service_case sc on sc.work_item_id=rd.work_item_id
                join work_item wi on wi.id=rd.work_item_id
                left join heir h on h.id=rd.heir_id
                where sc.id=? and df.id=? and sc.case_type='INHERITANCE_DEPOSIT' and wi.task_code='INHERITANCE_DEPOSIT'
                """, rs -> rs.next() ? new Reference(rs.getInt("revision"), rs.getInt("case_revision"),
                rs.getBoolean("is_latest"), rs.getTimestamp("deleted_at") == null,
                rs.getString("document_type_code"), asLong(rs.getObject("heir_id")),
                asLong(rs.getObject("heir_case_id")), rs.getString("heir_person_ref"), rs.getString("heir_name")) : null,
                caseId, document.documentFileId());
        if (reference == null || reference.revision() != revision || reference.fileRevision() != revision
                || !reference.latest() || !reference.notDeleted()
                || !ClovaTemplateOcrAdapter.ALLOWED_DOCUMENT_TYPE.equals(reference.documentType())) {
            return failedResults("STALE_OR_UNLINKED_DOCUMENT");
        }

        FieldValue grantors = readField(document.extracted(), "grantor_names");
        FieldValue agent = readField(document.extracted(), "agent_name");
        String personRef = normalize(document.personRef());
        boolean heirLinked = reference.heirId() != null && reference.heirCaseId() != null
                && reference.heirCaseId() == caseId && personRef != null
                && personRef.equals(normalize(reference.heirPersonRef()))
                && reference.heirName() != null && !reference.heirName().isBlank();
        FieldComparison grantorComparison;
        if (grantors.problem() != null) {
            grantorComparison = result("grantor_names", "MANUAL_CHECK", grantors.text(), null, grantors.confidence(), grantors.problem());
        } else if (!heirLinked) {
            grantorComparison = result("grantor_names", "UNVERIFIABLE", grantors.text(), null, grantors.confidence(), "HEIR_REFERENCE_NOT_LINKED_TO_DOCUMENT_SLOT");
        } else if (grantors.confidence() < minimumConfidence) {
            grantorComparison = result("grantor_names", "MANUAL_CHECK", grantors.text(), reference.heirName(), grantors.confidence(), "LOW_CONFIDENCE");
        } else {
            boolean match = normalize(grantors.text()).equals(normalize(reference.heirName()));
            grantorComparison = result("grantor_names", match ? "MATCH_CANDIDATE" : "MISMATCH_CANDIDATE",
                    grantors.text(), reference.heirName(), grantors.confidence(), match ? "TEXT_MATCH_CANDIDATE" : "TEXT_MISMATCH_CANDIDATE");
        }

        FieldComparison agentComparison;
        if (agent.problem() != null) {
            agentComparison = result("agent_name", "MANUAL_CHECK", agent.text(), null, agent.confidence(), agent.problem());
        } else if (agent.confidence() < minimumConfidence) {
            agentComparison = result("agent_name", "MANUAL_CHECK", agent.text(), null, agent.confidence(), "LOW_CONFIDENCE");
        } else {
            // This Case model has no explicit agent name for DOCUMENT delegation. Never infer one.
            agentComparison = result("agent_name", "UNVERIFIABLE", agent.text(), null, agent.confidence(), "NO_EXPLICIT_AGENT_REFERENCE");
        }
        return List.of(grantorComparison, agentComparison);
    }

    private List<FieldComparison> failedResults(String reason) {
        return List.of(result("grantor_names", "MANUAL_CHECK", null, null, null, reason),
                result("agent_name", "MANUAL_CHECK", null, null, null, reason));
    }

    private FieldValue readField(JsonNode extracted, String expectedName) {
        JsonNode fields = extracted == null ? null : extracted.get("fields");
        if (fields == null || !fields.isArray()) return new FieldValue(null, null, "FIELD_STRUCTURE_MISSING");
        List<JsonNode> matches = new ArrayList<>();
        fields.forEach(field -> { if (expectedName.equals(field.path("name").asText(null))) matches.add(field); });
        if (matches.size() != 1) return new FieldValue(null, null, matches.isEmpty() ? "FIELD_MISSING" : "FIELD_DUPLICATED");
        JsonNode value = matches.get(0).get("value");
        if (value == null || !value.isObject()) return new FieldValue(null, null, "FIELD_VALUE_INVALID");
        JsonNode text = value.get("text");
        JsonNode confidence = value.get("confidence");
        if (text == null || !text.isTextual() || text.asText().isBlank()) return new FieldValue(text != null && text.isTextual() ? text.asText() : null, null, "FIELD_TEXT_EMPTY");
        if (confidence == null || !confidence.isNumber() || !Double.isFinite(confidence.asDouble())
                || confidence.asDouble() < 0 || confidence.asDouble() > 1) {
            return new FieldValue(text.asText(), null, "CONFIDENCE_INVALID");
        }
        return new FieldValue(text.asText(), confidence.asDouble(), null);
    }

    private FieldComparison result(String name, String status, String extracted, String reference, Double confidence, String reason) {
        return new FieldComparison(name, status, extracted, reference, confidence,
                true, reason);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private Long asLong(Object value) { return value instanceof Number number ? number.longValue() : null; }

    private record FieldValue(String text, Double confidence, String problem) { }
    private record Reference(int revision, int fileRevision, boolean latest, boolean notDeleted,
                             String documentType, Long heirId, Long heirCaseId, String heirPersonRef, String heirName) { }
    public record FieldComparison(String fieldName, String status, String extractedValue, String referenceValue,
                                  Double confidence, boolean manualVerificationRequired, String reasonCode) { }
}
