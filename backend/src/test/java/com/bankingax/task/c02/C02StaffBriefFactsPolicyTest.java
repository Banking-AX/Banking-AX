package com.bankingax.task.c02;

import static org.assertj.core.api.Assertions.assertThat;

import com.bankingax.core.policychange.PolicyChangeReviewService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class C02StaffBriefFactsPolicyTest {
    /** The 2026-11-09 run passed only version, summary and state, so staff never saw what the change required. */
    @Test void anApprovedChangeCarriesWhatChangedItsChecksSourceAndScope() {
        Map<String, Object> facts = new HashMap<>();
        C02StaffBriefFacts.addPolicy(facts, List.of(new PolicyChangeReviewService.Notice(7, "v2", "위임서류 변경", "SCHEDULED", Instant.EPOCH, true, null, List.of("DELEGATION_METHOD_MATCH"),
                "v1", "인감 확인", "인감과 작성일 30일 확인", List.of("작성일 30일 이내 확인"), "DOC-1", "변경 안내")), 42L);

        @SuppressWarnings("unchecked") Map<String, Object> row = ((List<Map<String, Object>>) facts.get("approvedPolicyChanges")).get(0);
        assertThat(row).containsEntry("sourceVersion", "v2").containsEntry("before", "인감 확인").containsEntry("after", "인감과 작성일 30일 확인").containsEntry("addedCheckItems", List.of("작성일 30일 이내 확인"));
        assertThat(row.get("source")).isEqualTo(Map.of("documentId", "DOC-1", "title", "변경 안내"));
        assertThat(row.get("appliesTo")).isEqualTo(Map.of("taskCode", "INHERITANCE_DEPOSIT", "caseId", 42L, "reasonCodes", List.of("DELEGATION_METHOD_MATCH")));
        assertThat(facts).doesNotContainKey("policyNoticesUnavailable");
    }

    @Test void unreadableNoticesAreStatedInsteadOfLookingLikeNone() {
        Map<String, Object> facts = new HashMap<>();
        C02StaffBriefFacts.addPolicy(facts, null, 42L);

        assertThat(facts).containsEntry("policyNoticesUnavailable", true).doesNotContainKey("approvedPolicyChanges");
    }
}
