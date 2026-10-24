package com.bankingax.core.caseport;

import com.bankingax.core.workflow.TaskCode;
import java.util.Map;

public record ServiceCaseView(long caseId, long workItemId, TaskCode taskCode, CaseStatus caseStatus,
                              ReviewStatus reviewStatus, int revision, int version, boolean visitRequired,
                              Long decedentId, Map<String, Object> application) {
    public ServiceCaseView(long caseId, long workItemId, TaskCode taskCode, CaseStatus caseStatus,
                           ReviewStatus reviewStatus, int revision, int version, boolean visitRequired) {
        this(caseId, workItemId, taskCode, caseStatus, reviewStatus, revision, version, visitRequired, null, Map.of());
    }
}
