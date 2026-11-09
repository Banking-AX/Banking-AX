package com.bankingax.core.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class StaffRankingPortTest {
    private final List<StaffRankingPort.Candidate> candidates = List.of(
            new StaffRankingPort.Candidate(103, 1), new StaffRankingPort.Candidate(101, 2), new StaffRankingPort.Candidate(102, 1));

    @Test
    void mockRanksLowestWorkloadFirstThenId() {
        assertEquals(List.of(102L, 103L, 101L), new MockStaffRankingAdapter().rank(candidates, "INHERITANCE_DEPOSIT"));
    }

    @Test
    void httpAdapterFallsBackToWorkloadOrderWhenTheAgentIsUnreachable() {
        HttpStaffRankingAdapter adapter = new HttpStaffRankingAdapter(new ObjectMapper(), "http://127.0.0.1:1", "token");
        assertEquals(List.of(102L, 103L, 101L), adapter.rank(candidates, "INHERITANCE_DEPOSIT"));
    }

    @Test
    void httpAdapterWithoutUrlUsesWorkloadOrder() {
        assertEquals(List.of(102L, 103L, 101L), new HttpStaffRankingAdapter(new ObjectMapper(), "", "token").rank(candidates, "X"));
    }
}
