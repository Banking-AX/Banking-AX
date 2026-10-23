package com.bankingax.core.caseport;

import com.bankingax.core.workflow.TaskCode;

public record ServiceCaseView(long caseId, long workItemId, TaskCode taskCode, CaseStatus caseStatus,
                              ReviewStatus reviewStatus, int revision, int version, boolean visitRequired) { }
