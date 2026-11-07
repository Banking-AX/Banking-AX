package com.bankingax.task.c02;

import static org.assertj.core.api.Assertions.assertThat;

import com.bankingax.core.policychange.PolicyChangeReviewService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class C02StaffBriefFactsPolicyTest {
    @Test void approvedChangesCarryOnlyTheirVersionSummaryAndState() {
        Map<String, Object> facts = new HashMap<>();
        C02StaffBriefFacts.addPolicy(facts, List.of(new PolicyChangeReviewService.Notice(7, "v2", "위임서류 변경", "CURRENT", Instant.EPOCH, true, null, List.of("DOCUMENT"))));

        assertThat(facts.get("approvedPolicyChanges")).isEqualTo(List.of(Map.of("sourceVersion", "v2", "summary", "위임서류 변경", "effectiveState", "CURRENT")));
        assertThat(facts).doesNotContainKey("policyNoticesUnavailable");
    }

    @Test void unreadableNoticesAreStatedInsteadOfLookingLikeNone() {
        Map<String, Object> facts = new HashMap<>();
        C02StaffBriefFacts.addPolicy(facts, null);

        assertThat(facts).containsEntry("policyNoticesUnavailable", true).doesNotContainKey("approvedPolicyChanges");
    }
}
