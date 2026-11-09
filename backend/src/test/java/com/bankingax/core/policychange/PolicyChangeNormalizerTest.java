package com.bankingax.core.policychange;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class PolicyChangeNormalizerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PolicyChangeNormalizer normalizer = new PolicyChangeNormalizer();

    private ObjectNode event() throws Exception {
        return (ObjectNode) json.readTree("""
                {"sourceSystem":"BANK","changeEventId":"E1","externalPolicyId":"P1","sourceVersion":"v2","versionOrder":2,
                 "previousVersion":"v1","policyCategory":"OPERATION_GUIDE","changeType":"INFORMATION_UPDATE",
                 "affectedTaskCodes":["INHERITANCE_DEPOSIT"],"publishedAt":"2026-11-01T00:00:00Z",
                 "effectiveAt":"2026-12-01T00:00:00Z","sourceStatus":"PUBLISHED","changeSummary":"summary",
                 "sourceReference":{"documentId":"D1","title":"t","uri":"mock://d1"},"detail":{"before":"a","after":"b"}}
                """);
    }

    @Test void acceptsTheCanonicalEventWithoutAnyTaskSpecificField() throws Exception {
        var result = normalizer.normalize(event(), "BANK");

        assertThat(result.valid()).isTrue();
        assertThat(result.change().changeType()).isEqualTo(PolicyChangeType.INFORMATION_UPDATE);
        assertThat(result.change().extensionFor("INHERITANCE_DEPOSIT").isMissingNode()).isTrue();
    }

    @Test void anUnknownChangeTypeIsNamedNotDropped() throws Exception {
        assertThat(normalizer.normalize(event().put("changeType", "AUTO_APPLY_RULE"), "BANK").problems())
                .containsExactly("UNKNOWN_CHANGE_TYPE");
    }

    @Test void anEventFromAnotherSourceFails() throws Exception {
        assertThat(normalizer.normalize(event(), "OTHER").problems()).containsExactly("SOURCE_MISMATCH");
    }

    @Test void malformedTaskCodesAndEmptyListsFail() throws Exception {
        ObjectNode malformed = event();
        malformed.putArray("affectedTaskCodes").add("inheritance deposit!");
        ObjectNode empty = event();
        empty.putArray("affectedTaskCodes");

        assertThat(normalizer.normalize(malformed, "BANK").problems()).containsExactly("TASK_CODE_FORMAT_INVALID");
        assertThat(normalizer.normalize(empty, "BANK").problems()).containsExactly("AFFECTED_TASK_CODES_REQUIRED");
    }

    @Test void reportsEveryProblemAtOnce() throws Exception {
        ObjectNode event = event().put("versionOrder", 0).put("effectiveAt", "soon").put("sourceStatus", "DRAFT");
        event.remove("changeSummary");
        event.remove("detail");

        assertThat(normalizer.normalize(event, "BANK").problems()).containsExactlyInAnyOrder(
                "VERSION_ORDER_INVALID", "effectiveAt_INVALID", "UNKNOWN_SOURCE_STATUS", "changeSummary_REQUIRED", "DETAIL_INVALID");
    }

    @Test void onlyChangesThatAlterRulesAskForADevelopmentReview() {
        assertThat(PolicyChangeType.REVIEW_CRITERIA_CHANGE.ruleChangeReviewRequired()).isFalse();
        assertThat(PolicyChangeType.DOCUMENT_REQUIREMENT_CHANGE.ruleChangeReviewRequired()).isTrue();
    }
}
