package com.bankingax.core.chat;

import com.bankingax.core.api.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class ChatAgentService {
    private static final int RECENT_MESSAGE_LIMIT = 5;

    private final ChatService chats;
    private final CustomerAgentClient agent;
    private final ObjectMapper json;

    public ChatAgentService(ChatService chats, CustomerAgentClient agent, ObjectMapper json) {
        this.chats = chats;
        this.agent = agent;
        this.json = json;
    }

    public AgentRunView run(
            long customerId,
            long chatRoomId,
            String messageRequestId,
            String operationRequestId,
            String content) {
        requireOperationRequestId(operationRequestId);
        ChatService.ChatMessageView customerMessage =
                chats.saveCustomerMessage(customerId, chatRoomId, messageRequestId, content);

        List<String> recentMessages = recentMessages(
                chats.listMessages(customerId, chatRoomId), customerMessage.seq());
        CustomerAgentClient.AgentResponse result = agent.start(new CustomerAgentClient.AgentRequest(
                operationRequestId, customerId, chatRoomId, content, recentMessages));
        validateResponse(operationRequestId, customerId, result);

        ChatService.ChatMessageView agentMessage = null;
        if (result.answer() != null && !result.answer().isBlank()) {
            agentMessage = chats.saveAgentMessage(
                    customerId, chatRoomId, agentMessageRequestId(operationRequestId), result.answer(),
                    actionMetadata(result));
        }
        boolean completed = result.executionStatus() != null
                && "DONE".equals(result.executionStatus().workItemStatus())
                && "COMPLETED".equals(result.executionStatus().workflowStatus());
        return new AgentRunView(
                customerMessage, agentMessage, result, completed, agentMessage != null);
    }

    private String actionMetadata(CustomerAgentClient.AgentResponse result) {
        if (result.taskAction() == null || result.taskAction().isNull()) {
            return null;
        }
        try {
            return json.writeValueAsString(Map.of("taskAction", result.taskAction()));
        } catch (JsonProcessingException exception) {
            throw new DomainException(
                    "CUSTOMER_AGENT_INVALID_RESPONSE", "Customer Agent action could not be stored.");
        }
    }

    private static List<String> recentMessages(
            List<ChatService.ChatMessageView> messages, int currentMessageSequence) {
        List<String> previous = messages.stream()
                .filter(message -> message.seq() < currentMessageSequence)
                .map(message -> message.senderType().name() + ": " + message.content())
                .toList();
        int from = Math.max(0, previous.size() - RECENT_MESSAGE_LIMIT);
        return previous.subList(from, previous.size());
    }

    private static void validateResponse(
            String operationRequestId, long customerId, CustomerAgentClient.AgentResponse response) {
        if (response == null
                || !operationRequestId.equals(response.requestId())
                || customerId != response.customerId()) {
            throw new DomainException(
                    "CUSTOMER_AGENT_INVALID_RESPONSE", "Customer Agent returned an invalid response.", true);
        }
    }

    private static void requireOperationRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 90) {
            throw new DomainException(
                    "INVALID_REQUEST", "An operationRequestId of at most 90 characters is required.");
        }
    }

    private static String agentMessageRequestId(String operationRequestId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(operationRequestId.getBytes(StandardCharsets.UTF_8));
            return "agent-" + HexFormat.of().formatHex(digest, 0, 24);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record AgentRunView(
            ChatService.ChatMessageView customerMessage,
            ChatService.ChatMessageView agentMessage,
            CustomerAgentClient.AgentResponse agentResult,
            boolean financialOperationCompleted,
            boolean agentMessageSaved) { }
}
