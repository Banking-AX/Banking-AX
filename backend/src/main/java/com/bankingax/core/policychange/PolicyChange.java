package com.bankingax.core.policychange;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * Internal form of one bank change event. It has no task-specific field: task detail stays in
 * {@code detail.taskExtensions.<taskCode>} and only that task's profile reads it.
 */
public record PolicyChange(String sourceSystem, String changeEventId, String externalPolicyId, String sourceVersion,
                           long versionOrder, String previousVersion, String policyCategory,
                           PolicyChangeType changeType, List<String> affectedTaskCodes, Instant publishedAt,
                           Instant effectiveAt, String sourceStatus, String changeSummary,
                           SourceReference sourceReference, JsonNode detail) {
    public record SourceReference(String documentId, String title, String uri) { }

    public JsonNode extensionFor(String taskCode) { return detail.path("taskExtensions").path(taskCode); }
}
