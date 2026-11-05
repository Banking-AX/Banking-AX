package com.bankingax.core.reservation;
import java.util.Comparator;
import java.util.List;
/**
 * Ranks Spring-filtered staff candidates. Only the task code and the current workload Spring counted are
 * inputs (spec 3-3: no subjective experience scores); Spring still makes and re-checks the final assignment.
 */
public interface StaffRankingPort {
    List<Long> rank(List<Candidate> candidates, String taskCode);

    record Candidate(long staffId, int activeAssignments) { }

    /** Deterministic order used by the mock and as the HTTP fallback: lowest workload first, then id. */
    static List<Long> byWorkload(List<Candidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::activeAssignments).thenComparingLong(Candidate::staffId))
                .map(Candidate::staffId).toList();
    }
}
