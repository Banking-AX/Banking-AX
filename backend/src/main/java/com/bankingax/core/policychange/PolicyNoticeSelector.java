package com.bankingax.core.policychange;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chooses what staff see for one bank policy. "Newest received" and "approved and valid" are different questions:
 * an unreviewed newer version never removes an approved notice, while an effective bank withdrawal always does,
 * because withdrawing is the bank's own decision and only stops a notice. This is never a rule or a Case input.
 */
final class PolicyNoticeSelector {
    private static final String WITHDRAWN = "WITHDRAWN";

    private PolicyNoticeSelector() { }

    record Version(long id, long order, String sourceVersion, String sourceStatus, Instant effectiveAt, String decision) {
        boolean withdrawal() { return WITHDRAWN.equals(sourceStatus); }
        boolean effectiveAt(Instant now) { return !effectiveAt.isAfter(now); }
        boolean approved() { return PolicyChangeReviewService.Decision.NOTICE_APPROVED.name().equals(decision); }
    }

    record Approved(Version version, String newerBankVersion) { }

    record Selection(List<Approved> approved, Optional<Version> pending) { }

    static Selection select(List<Version> versions, Instant now) {
        long withdrawnThrough = versions.stream().filter(row -> row.withdrawal() && row.effectiveAt(now))
                .mapToLong(Version::order).max().orElse(0);
        List<Version> valid = versions.stream().filter(Version::approved).filter(row -> !row.withdrawal())
                .filter(row -> row.order() > withdrawnThrough).sorted(Comparator.comparingLong(Version::order)).toList();
        Optional<Version> current = valid.stream().filter(row -> row.effectiveAt(now)).max(Comparator.comparingLong(Version::order));
        long currentOrder = current.map(Version::order).orElse(0L);

        List<Approved> approved = new ArrayList<>();
        current.ifPresent(row -> approved.add(new Approved(row, newerInEffect(row, versions, now))));
        valid.stream().filter(row -> !row.effectiveAt(now) && row.order() > currentOrder)
                .max(Comparator.comparingLong(Version::order)).ifPresent(row -> approved.add(new Approved(row, null)));

        Optional<Version> pending = versions.stream().max(Comparator.comparingLong(Version::order))
                .filter(row -> Objects.isNull(row.decision()));
        return new Selection(approved, pending);
    }

    /** The bank has moved on but no one approved that wording: keep the notice and say so instead of hiding it. */
    private static String newerInEffect(Version shown, List<Version> versions, Instant now) {
        return versions.stream().filter(row -> row.order() > shown.order() && !row.withdrawal() && row.effectiveAt(now))
                .max(Comparator.comparingLong(Version::order)).map(Version::sourceVersion).orElse(null);
    }
}
