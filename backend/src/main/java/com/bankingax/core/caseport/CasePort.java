package com.bankingax.core.caseport;

import com.bankingax.core.workflow.TaskCode;
import java.util.Map;
import java.util.Optional;

public interface CasePort {
    ServiceCaseView create(CreateCaseCommand command);
    ServiceCaseView submit(long actorId, long caseId, int expectedRevision, String requestId);
    ServiceCaseView revise(long actorId, long caseId, int expectedRevision, Map<String, Object> application, String reason);
    ServiceCaseView cancel(long actorId, long caseId, int expectedVersion);
    ServiceCaseView recordCheckResult(long caseId, int revision, ReviewStatus status);
    ServiceCaseView get(long actorId, long caseId);
    Optional<ServiceCaseView> findByWorkItem(long actorId, long workItemId, TaskCode taskCode);
}
