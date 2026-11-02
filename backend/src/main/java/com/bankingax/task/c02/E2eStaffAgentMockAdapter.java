package com.bankingax.task.c02;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Deterministic staff-precheck fixture. It never records a human decision. */
@Component
@Primary
@Profile("e2e")
public class E2eStaffAgentMockAdapter implements StaffAgentPort {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public StaffAgentResponse check(String requestId, long caseId, int revision, Map<String, Object> snapshot) {
        calls.incrementAndGet();
        boolean missing = snapshot.get("missingDocuments") instanceof List<?> items && !items.isEmpty();
        String status = missing ? "WAITING_CUSTOMER" : "READY_FOR_STAFF";
        Map<String, Object> item = Map.of(
                "source", "PRE_SUBMISSION",
                "category", missing ? "REQUIRED_DOCUMENT_MISSING" : "MANUAL_DOCUMENT_REVIEW",
                "result", missing ? "NEEDS_CUSTOMER_SUPPLEMENT" : "STAFF_REVIEW_REQUIRED",
                "message", missing ? "Required document slots remain incomplete." : "OCR is reference material; staff must inspect source documents.",
                "evidence", Map.of("revision", revision, "mock", true));
        return new StaffAgentResponse("SUCCEEDED", status, List.of(item), null);
    }

    public int callCount() { return calls.get(); }
    void reset() { calls.set(0); }
}
