package com.bankingax.core.chat;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/chat/rooms")
public class ChatAgentController {
    private static final String TASK_CODE = "CHAT_AGENT";

    private final ChatAgentService agents;
    private final TaskBffPrincipal principals;

    public ChatAgentController(ChatAgentService agents, TaskBffPrincipal principals) {
        this.agents = agents;
        this.principals = principals;
    }

    @PostMapping("/{roomId}/agent-runs")
    public ApiEnvelope<ChatAgentService.AgentRunView> run(
            JwtAuthenticationToken authentication,
            @PathVariable long roomId,
            @RequestBody AgentRunRequest request) {
        if (!"CUSTOMER".equals(authentication.getToken().getClaimAsString("role"))) {
            throw new DomainException("FORBIDDEN", "Customer access is required.");
        }
        long customerId = principals.customer(authentication);
        ChatAgentService.AgentRunView result = agents.run(
                customerId,
                roomId,
                request.messageRequestId(),
                request.operationRequestId(),
                request.content());
        return ApiEnvelope.success(
                request.operationRequestId(), TASK_CODE, workItemId(result), null, null, result);
    }

    private static Long workItemId(ChatAgentService.AgentRunView result) {
        return result.agentResult().executionStatus() == null
                ? null : result.agentResult().executionStatus().workItemId();
    }

    public record AgentRunRequest(
            String messageRequestId, String operationRequestId, String content) { }
}
