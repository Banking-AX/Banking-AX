package com.bankingax.core.policychange;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Receives bank events into the register and does nothing else: no rule, Case, document requirement or RAG entry
 * changes here. Each event stands alone, so one bad event cannot block the rest of a feed page.
 */
@Service
@ConditionalOnDataSource
public class PolicyChangeIngestService {
    static final String ACCEPTED = "ACCEPTED", STALE = "STALE", OUT_OF_SCOPE = "OUT_OF_SCOPE";
    private static final String QUARANTINED = "QUARANTINED", DUPLICATE = "DUPLICATE";

    private final PolicyChangeProvider provider;
    private final PolicyChangeNormalizer normalizer;
    private final PolicyTaskRegistry tasks;
    private final PolicyChangeStore store;

    public PolicyChangeIngestService(PolicyChangeProvider provider, PolicyChangeNormalizer normalizer,
                                     PolicyTaskRegistry tasks, PolicyChangeStore store) {
        this.provider = provider;
        this.normalizer = normalizer;
        this.tasks = tasks;
        this.store = store;
    }

    public SyncResult sync(long staffId, String requestId) {
        String source = provider.sourceSystem();
        String cursor = store.lastCursor(source).orElse(null);
        PolicyChangeProvider.Feed feed;
        try {
            feed = provider.fetch(cursor);
        } catch (DomainException exception) {
            store.insertSync(source, PolicyChangeStore.SYNC_FAILED, cursor, staffId, requestId, Map.of("code", exception.code()));
            throw exception;
        }
        List<String> outcomes = new ArrayList<>();
        for (JsonNode raw : feed.events()) outcomes.add(ingest(raw, source));
        SyncResult result = new SyncResult(feed.events().size(), count(outcomes, ACCEPTED), count(outcomes, STALE),
                count(outcomes, OUT_OF_SCOPE), count(outcomes, QUARANTINED), count(outcomes, DUPLICATE));
        store.insertSync(source, PolicyChangeStore.SYNC_COMPLETED, feed.nextCursor(), staffId, requestId, result);
        return result;
    }

    private String ingest(JsonNode raw, String source) {
        PolicyChangeNormalizer.Result normalized = normalizer.normalize(raw, source);
        if (!normalized.valid()) return quarantine(source, raw, normalized.problems());

        PolicyChange change = normalized.change();
        String hash = store.contentHash(change);
        Optional<PolicyChangeStore.Received> sameEvent = store.findByEvent(change.sourceSystem(), change.changeEventId());
        if (sameEvent.isPresent())
            return sameEvent.get().contentHash().equals(hash) ? DUPLICATE : quarantine(source, raw, List.of("EVENT_CONTENT_CONFLICT"));

        List<PolicyChangeStore.Received> history = store.history(change.sourceSystem(), change.externalPolicyId());
        Optional<PolicyChangeStore.Received> sameVersion = history.stream()
                .filter(row -> row.change().versionOrder() == change.versionOrder()
                        || row.change().sourceVersion().equals(change.sourceVersion())).findFirst();
        if (sameVersion.isPresent())
            return sameVersion.get().contentHash().equals(hash) ? DUPLICATE : quarantine(source, raw, List.of("VERSION_CONTENT_CONFLICT"));

        List<String> mapped = change.affectedTaskCodes().stream().filter(tasks::registered).toList();
        List<String> unmapped = change.affectedTaskCodes().stream().filter(code -> !tasks.registered(code)).toList();
        List<String> extensionProblems = mapped.stream()
                .flatMap(code -> tasks.profile(code).validateExtension(change.extensionFor(code)).stream()).toList();
        if (!extensionProblems.isEmpty()) return quarantine(source, raw, extensionProblems);

        Optional<PolicyChangeStore.Received> latest = history.stream().filter(row -> ACCEPTED.equals(row.intakeStatus()))
                .max(Comparator.comparingLong(row -> row.change().versionOrder()));
        boolean older = latest.isPresent() && change.versionOrder() < latest.get().change().versionOrder();
        boolean gap = latest.isPresent() && !older
                && !Objects.equals(change.previousVersion(), latest.get().change().sourceVersion());
        String status = mapped.isEmpty() ? OUT_OF_SCOPE : older ? STALE : ACCEPTED;
        return store.insertReceived(change, hash, status, gap, mapped, unmapped) ? status : DUPLICATE;
    }

    private String quarantine(String source, JsonNode raw, List<String> problems) {
        store.insertQuarantined(source, raw, problems);
        return QUARANTINED;
    }

    private static int count(List<String> outcomes, String value) {
        return (int) outcomes.stream().filter(value::equals).count();
    }

    public record SyncResult(int fetched, int accepted, int stale, int outOfScope, int quarantined, int duplicate) { }
}
