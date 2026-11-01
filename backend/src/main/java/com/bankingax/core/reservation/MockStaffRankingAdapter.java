package com.bankingax.core.reservation;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
/** Deterministic candidate ordering only; Spring remains the authorization source. */
@Component @ConditionalOnProperty(name="app.reservation.ranking-provider",havingValue="mock",matchIfMissing=true) public class MockStaffRankingAdapter implements StaffRankingPort { public List<Long> rank(List<Long> ids,String task){return ids.stream().sorted().toList();} }
