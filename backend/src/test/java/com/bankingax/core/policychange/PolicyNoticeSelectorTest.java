package com.bankingax.core.policychange;

import static org.assertj.core.api.Assertions.assertThat;

import com.bankingax.core.policychange.PolicyNoticeSelector.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyNoticeSelectorTest {
    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final Instant PAST = NOW.minus(Duration.ofDays(3)), FUTURE = NOW.plus(Duration.ofDays(3));
    private static final String APPROVED = "NOTICE_APPROVED";

    private static Version version(int order, Instant effectiveAt, String decision) {
        return new Version(order, order, "v" + order, "PUBLISHED", effectiveAt, decision);
    }

    private static Version withdrawal(int order, Instant effectiveAt) {
        return new Version(order, order, "v" + order, "WITHDRAWN", effectiveAt, null);
    }

    private static List<String> shown(PolicyNoticeSelector.Selection selection) {
        return selection.approved().stream().map(row -> row.version().sourceVersion()).toList();
    }

    @Test void anUnreviewedScheduledVersionKeepsTheApprovedNoticeAndWaitsAsPending() {
        var selection = PolicyNoticeSelector.select(List.of(version(2, PAST, APPROVED), version(3, FUTURE, null)), NOW);

        assertThat(shown(selection)).containsExactly("v2");
        assertThat(selection.approved().get(0).newerBankVersion()).isNull();
        assertThat(selection.pending().orElseThrow().sourceVersion()).isEqualTo("v3");
    }

    @Test void anUnreviewedVersionAlreadyInEffectKeepsTheNoticeButFlagsIt() {
        var selection = PolicyNoticeSelector.select(List.of(version(2, PAST, APPROVED), version(3, PAST, null)), NOW);

        assertThat(shown(selection)).containsExactly("v2");
        assertThat(selection.approved().get(0).newerBankVersion()).isEqualTo("v3");
    }

    @Test void anApprovedScheduledVersionAppearsBesideTheCurrentOneUntilItTakesEffect() {
        var before = PolicyNoticeSelector.select(List.of(version(2, PAST, APPROVED), version(3, FUTURE, APPROVED)), NOW);
        var after = PolicyNoticeSelector.select(List.of(version(2, PAST, APPROVED), version(3, FUTURE, APPROVED)), FUTURE.plusSeconds(1));

        assertThat(shown(before)).containsExactly("v2", "v3");
        assertThat(shown(after)).containsExactly("v3");
    }

    @Test void onlyTheNewestApprovedScheduledVersionIsAnnounced() {
        var selection = PolicyNoticeSelector.select(List.of(version(2, FUTURE, APPROVED), version(3, FUTURE, APPROVED)), NOW);

        assertThat(shown(selection)).containsExactly("v3");
    }

    @Test void aDecisionNotToNotifyLeavesTheEarlierApprovedNoticeInPlace() {
        for (String decision : List.of("DEV_REQUIRED", "NO_ACTION")) {
            var selection = PolicyNoticeSelector.select(List.of(version(2, PAST, APPROVED), version(3, PAST, decision)), NOW);

            assertThat(shown(selection)).containsExactly("v2");
            assertThat(selection.pending()).isEmpty();
        }
    }

    @Test void anEffectiveWithdrawalEndsEveryEarlierNoticeWithoutWaitingForReview() {
        var selection = PolicyNoticeSelector.select(List.of(version(2, PAST, APPROVED), withdrawal(3, PAST)), NOW);

        assertThat(shown(selection)).isEmpty();
        assertThat(selection.pending().orElseThrow().withdrawal()).isTrue();
    }

    @Test void aFutureWithdrawalKeepsTheNoticeUntilItTakesEffect() {
        var selection = PolicyNoticeSelector.select(List.of(version(2, PAST, APPROVED), withdrawal(3, FUTURE)), NOW);

        assertThat(shown(selection)).containsExactly("v2");
        assertThat(selection.pending().orElseThrow().withdrawal()).isTrue();
    }

    @Test void aVersionPublishedAfterAWithdrawalCanBeShownAgain() {
        var selection = PolicyNoticeSelector.select(List.of(version(2, PAST, APPROVED), withdrawal(3, PAST), version(4, PAST, APPROVED)), NOW);

        assertThat(shown(selection)).containsExactly("v4");
    }

    @Test void nothingIsShownBeforeAnyApproval() {
        var selection = PolicyNoticeSelector.select(List.of(version(2, PAST, null)), NOW);

        assertThat(selection.approved()).isEmpty();
        assertThat(selection.pending().orElseThrow().sourceVersion()).isEqualTo("v2");
    }

    @Test void onlyTheNewestUndecidedVersionWaitsForReview() {
        var selection = PolicyNoticeSelector.select(List.of(version(2, PAST, null), version(3, FUTURE, null)), NOW);

        assertThat(selection.pending().orElseThrow().sourceVersion()).isEqualTo("v3");
    }
}
