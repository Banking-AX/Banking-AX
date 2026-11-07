package com.bankingax.core.policychange;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Validates one canonical event. Every problem is named so a quarantined event can be corrected at the source. */
@Component
public class PolicyChangeNormalizer {
    private static final Pattern TASK_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Set<String> SOURCE_STATUSES = Set.of("PUBLISHED", "WITHDRAWN");
    private static final int ID_LIMIT = 40, VERSION_LIMIT = 60, TEXT_LIMIT = 300;

    public Result normalize(JsonNode raw, String expectedSource) {
        List<String> problems = new ArrayList<>();
        String source = text(raw, "sourceSystem", ID_LIMIT, problems);
        if (source != null && !source.equals(expectedSource)) problems.add("SOURCE_MISMATCH");
        String eventId = text(raw, "changeEventId", VERSION_LIMIT, problems);
        String policyId = text(raw, "externalPolicyId", ID_LIMIT, problems);
        String version = text(raw, "sourceVersion", VERSION_LIMIT, problems);
        String category = text(raw, "policyCategory", ID_LIMIT, problems);
        String summary = text(raw, "changeSummary", TEXT_LIMIT, problems);
        String previous = raw.path("previousVersion").isTextual() ? raw.path("previousVersion").asText() : null;
        if (previous != null && previous.length() > VERSION_LIMIT) problems.add("PREVIOUS_VERSION_TOO_LONG");

        long order = raw.path("versionOrder").canConvertToLong() && raw.path("versionOrder").isIntegralNumber()
                ? raw.path("versionOrder").asLong() : 0;
        if (order < 1) problems.add("VERSION_ORDER_INVALID");

        PolicyChangeType type = changeType(raw.path("changeType").asText(""), problems);
        String status = raw.path("sourceStatus").asText("");
        if (!SOURCE_STATUSES.contains(status)) problems.add("UNKNOWN_SOURCE_STATUS");
        List<String> tasks = taskCodes(raw.path("affectedTaskCodes"), problems);
        Instant publishedAt = instant(raw, "publishedAt", problems);
        Instant effectiveAt = instant(raw, "effectiveAt", problems);

        JsonNode reference = raw.path("sourceReference");
        String documentId = text(reference, "documentId", VERSION_LIMIT, problems);
        JsonNode detail = raw.path("detail");
        if (!detail.isObject() || !detail.path("after").isTextual()) problems.add("DETAIL_INVALID");

        if (!problems.isEmpty()) return new Result(null, problems);
        return new Result(new PolicyChange(source, eventId, policyId, version, order, previous, category, type, tasks,
                publishedAt, effectiveAt, status, summary, new PolicyChange.SourceReference(documentId,
                reference.path("title").asText(null), reference.path("uri").asText(null)), detail), List.of());
    }

    private static PolicyChangeType changeType(String value, List<String> problems) {
        try { return PolicyChangeType.valueOf(value); }
        catch (IllegalArgumentException exception) { problems.add("UNKNOWN_CHANGE_TYPE"); return null; }
    }

    private static List<String> taskCodes(JsonNode node, List<String> problems) {
        List<String> codes = new ArrayList<>();
        if (node.isArray()) node.forEach(code -> codes.add(code.asText("")));
        if (codes.isEmpty()) problems.add("AFFECTED_TASK_CODES_REQUIRED");
        if (!codes.stream().allMatch(code -> TASK_CODE.matcher(code).matches())) problems.add("TASK_CODE_FORMAT_INVALID");
        return List.copyOf(codes);
    }

    private static Instant instant(JsonNode raw, String field, List<String> problems) {
        try { return Instant.parse(raw.path(field).asText("")); }
        catch (DateTimeParseException exception) { problems.add(field + "_INVALID"); return null; }
    }

    private static String text(JsonNode raw, String field, int limit, List<String> problems) {
        String value = raw.path(field).isTextual() ? raw.path(field).asText().trim() : "";
        if (value.isEmpty()) { problems.add(field + "_REQUIRED"); return null; }
        if (value.length() > limit) { problems.add(field + "_TOO_LONG"); return null; }
        return value;
    }

    public record Result(PolicyChange change, List<String> problems) {
        public boolean valid() { return problems.isEmpty(); }
    }
}
