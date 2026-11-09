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
            B01LoanSelectionAction b01LoanSelectionAction,
            B01DocumentAction b01DocumentAction,
            B01ReconnectAction b01ReconnectAction,
            String answer,
            String answerStatus) { }

    record B01LoanSelectionAction(String type, List<B01LoanSelectionCandidate> candidates) {
        public B01LoanSelectionAction {
            if (!"SELECT_B01_LOAN".equals(type) || candidates == null || candidates.isEmpty()) {
                throw new IllegalArgumentException("Invalid B-01 loan selection action.");
            }
            Set<Integer> numbers = new HashSet<>();
            Set<Long> loanIds = new HashSet<>();
            for (B01LoanSelectionCandidate candidate : candidates) {
                if (candidate == null || !numbers.add(candidate.number()) || !loanIds.add(candidate.loanId())) {
                    throw new IllegalArgumentException("Invalid B-01 loan selection candidates.");
                }
            }
            candidates = List.copyOf(candidates);
        }
    }

    record B01LoanSelectionCandidate(int number, long loanId, String loanName, String loanNoMasked) {
        public B01LoanSelectionCandidate {
            if (number <= 0 || loanId <= 0 || loanName == null || loanName.isBlank()
                    || loanNoMasked == null || !loanNoMasked.contains("*")) {
                throw new IllegalArgumentException("Invalid B-01 loan selection candidate.");
            }
        }
    }

    record B01DocumentAction(String type, String fileName, String downloadPath) {
        public B01DocumentAction {
            if (!"DOWNLOAD_B01_APPLICATION".equals(type)
                    || !"b01-repayment-order-change-application-201903.pdf".equals(fileName)
                    || !"/api/v1/customer/b01/application-form".equals(downloadPath)) {
                throw new IllegalArgumentException("Invalid B-01 document action.");
            }
        }
    }

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
