package com.bankingax.task.c02;

import java.util.Map;

/** Spring-owned transport boundary; a staff Agent report is never a final staff decision. */
public interface StaffAgentPort {
    StaffAgentResponse check(String requestId, long caseId, int revision, Map<String, Object> caseSnapshot);
    record StaffAgentResponse(String status, String reviewStatus, java.util.List<Map<String, Object>> reviewItems, Map<String, Object> error) { }
}
