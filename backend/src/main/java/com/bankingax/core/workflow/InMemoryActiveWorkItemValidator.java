package com.bankingax.core.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Contract-fixture validator used only while Workflow/WorkItem persistence is configured in memory. */
@Component
@ConditionalOnProperty(name = "app.case-port", havingValue = "inmemory", matchIfMissing = true)
class InMemoryActiveWorkItemValidator implements ActiveWorkItemValidator {
    @Override
    public void validate(Long workItemId, long customerId, TaskCode expectedTaskCode) {
        // The current in-memory fixture has no queryable WorkItem store. JPA performs the real validation.
    }
}
