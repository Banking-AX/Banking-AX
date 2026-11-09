package com.bankingax.core.policychange;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** An unattended poll must not fill the audit list with empty runs or one failure per minute. */
class PolicyChangeAutoSyncTest {
    private final PolicyChangeProvider provider = mock(PolicyChangeProvider.class);
    private final PolicyChangeNormalizer normalizer = mock(PolicyChangeNormalizer.class);
    private final PolicyTaskRegistry tasks = mock(PolicyTaskRegistry.class);
    private final PolicyChangeStore store = mock(PolicyChangeStore.class);
    private final PolicyChangeIngestService ingest = new PolicyChangeIngestService(provider, normalizer, tasks, store);
    private final JsonNode event = new ObjectMapper().createObjectNode().put("changeEventId", "E1");

    @BeforeEach void source() {
        when(provider.sourceSystem()).thenReturn("MOCK_BANK_POLICY");
        when(store.lastCursor("MOCK_BANK_POLICY")).thenReturn(Optional.of("5"));
    }

    @Test void anEmptyPollLeavesNoRecord() {
        when(provider.fetch("5")).thenReturn(new PolicyChangeProvider.Feed(List.of(), "5"));
        ingest.syncAutomatically("auto-1");
        verify(store, never()).insertSync(any(), any(), any(), any(), any(), any());
    }

    @Test void receivedEventsAreRecordedAsASystemRun() {
        when(provider.fetch("5")).thenReturn(new PolicyChangeProvider.Feed(List.of(event), "9"));
        when(normalizer.normalize(event, "MOCK_BANK_POLICY")).thenReturn(new PolicyChangeNormalizer.Result(null, List.of("DETAIL_INVALID")));
        ingest.syncAutomatically("auto-2");
        verify(store).insertSync(eq("MOCK_BANK_POLICY"), eq(PolicyChangeStore.SYNC_COMPLETED), eq("9"), isNull(), eq("auto-2"), any());
    }

    @Test void repeatedFailuresAreRecordedOnceUntilTheSourceRecovers() {
        when(provider.fetch("5")).thenThrow(new DomainException("POLICY_SOURCE_UNAVAILABLE", "down", true));
        ingest.syncAutomatically("auto-3");
        ingest.syncAutomatically("auto-4");
        verify(store, times(1)).insertSync(any(), eq(PolicyChangeStore.SYNC_FAILED), any(), isNull(), anyString(), any());

        org.mockito.Mockito.reset(provider);
        when(provider.sourceSystem()).thenReturn("MOCK_BANK_POLICY");
        when(provider.fetch("5")).thenReturn(new PolicyChangeProvider.Feed(List.of(), "5"));
        ingest.syncAutomatically("auto-5");
        org.mockito.Mockito.reset(provider);
        when(provider.sourceSystem()).thenReturn("MOCK_BANK_POLICY");
        when(provider.fetch("5")).thenThrow(new DomainException("POLICY_SOURCE_UNAVAILABLE", "down", true));
        ingest.syncAutomatically("auto-6");
        verify(store, times(2)).insertSync(any(), eq(PolicyChangeStore.SYNC_FAILED), any(), isNull(), anyString(), any());
    }
}
