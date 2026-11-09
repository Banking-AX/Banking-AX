package com.bankingax.core.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            JsonNode taskAction,
            B01ReconnectAction b01ReconnectAction,
            String answer,
            String answerStatus) { }

    record B01ReconnectAction(
            String type,
            boolean allowStartNew,
            List<B01ReconnectCandidate> candidates) {
        public B01ReconnectAction {
            if (!"SELECT_B01_RECONNECT".equals(type)
                    || candidates == null || candidates.isEmpty()) {
                throw new IllegalArgumentException("Invalid B-01 reconnect action.");
            }
            Set<Long> workItemIds = new HashSet<>();
            for (B01ReconnectCandidate candidate : candidates) {
                if (candidate == null || !workItemIds.add(candidate.workItemId())) {
                    throw new IllegalArgumentException("Invalid B-01 reconnect candidates.");
                }
            }
            candidates = List.copyOf(candidates);
        }
    }

    record B01ReconnectCandidate(
            long workItemId,
            String status,
            Long originalChatRoomId,
            boolean originalChatRoomAvailable) {
        public B01ReconnectCandidate {
            if (workItemId <= 0
                    || !("ACTIVE".equals(status) || "ON_HOLD".equals(status))
                    || (originalChatRoomId != null && originalChatRoomId <= 0)
                    || (originalChatRoomAvailable && originalChatRoomId == null)) {
                throw new IllegalArgumentException("Invalid B-01 reconnect candidate.");
            }
        }
    }

    record WorkflowView(long workflowId, long workItemId, String taskCode, String status) { }

    record ExecutionStatus(
            long workflowId,
            long workItemId,
            String taskCode,
            String workItemStatus,
            String workflowStatus) { }
}
