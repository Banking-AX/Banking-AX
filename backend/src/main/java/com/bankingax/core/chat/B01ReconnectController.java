package com.bankingax.core.chat;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.jpa.B01ReconnectService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/chat/rooms")
@ConditionalOnBean(B01ReconnectService.class)
public class B01ReconnectController {
    private final B01ReconnectService reconnects;
    private final TaskBffPrincipal principals;

    public B01ReconnectController(B01ReconnectService reconnects, TaskBffPrincipal principals) {
        this.reconnects = reconnects;
        this.principals = principals;
    }

    @PostMapping("/{roomId}/b01/reconnect")
    public ApiEnvelope<B01ReconnectService.ReconnectResult> reconnect(
            JwtAuthenticationToken authentication,
            @PathVariable long roomId,
            @RequestBody ReconnectRequest request) {
        if (!"CUSTOMER".equals(authentication.getToken().getClaimAsString("role"))) {
            throw new DomainException("FORBIDDEN", "Customer access is required.");
        }
        B01ReconnectService.ReconnectChoice choice = parseChoice(request == null ? null : request.choice());
        long customerId = principals.customer(authentication);
        B01ReconnectService.ReconnectResult result = reconnects.reconnect(
                customerId, roomId, new B01ReconnectService.ReconnectCommand(
                        request.requestId(), choice, request.workItemId()));
        return ApiEnvelope.success(
                request.requestId(), TaskCode.REPAYMENT_ORDER_CHANGE.name(),
                result.workItemId(), null, null, result);
    }

    private static B01ReconnectService.ReconnectChoice parseChoice(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainException("INVALID_REQUEST", "A B-01 reconnect choice is required.");
        }
        try {
            return B01ReconnectService.ReconnectChoice.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new DomainException("INVALID_REQUEST", "A supported B-01 reconnect choice is required.");
        }
    }

    public record ReconnectRequest(String requestId, String choice, Long workItemId) { }
}
