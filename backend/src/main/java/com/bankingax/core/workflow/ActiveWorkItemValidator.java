package com.bankingax.core.workflow;

/** Verifies that a customer may execute the expected task through a persisted active WorkItem. */
@FunctionalInterface
public interface ActiveWorkItemValidator {
    void validate(Long workItemId, long customerId, TaskCode expectedTaskCode);
}
