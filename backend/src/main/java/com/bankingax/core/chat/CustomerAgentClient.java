package com.bankingax.core.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface CustomerAgentClient {
    AgentResponse start(AgentRequest request);

    record AgentRequest(
            String requestId,
            long customerId,
            long chatRoomId,
            String message,
            List<String> recentMessages) { }

    record AgentResponse(
            String requestId,
            long customerId,
            String outcome,
            JsonNode classification,
            WorkflowView workflow,
            JsonNode loanInquiry,
            ExecutionStatus executionStatus,
            String answer,
            String answerStatus) { }

    record WorkflowView(long workflowId, long workItemId, String taskCode, String status) { }

    record ExecutionStatus(
            long workflowId,
            long workItemId,
            String taskCode,
            String workItemStatus,
            String workflowStatus) { }
}
