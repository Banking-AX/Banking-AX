package com.bankingax.core.notification;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.tool.ActorRole;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** JWT customer boundary for the shared notification API. */
@RestController
@RequestMapping("/api/v1/customer/notifications")
@ConditionalOnBean(JdbcTemplate.class)
public class CustomerNotificationBffController {
    private final NotificationController internal;
    private final TaskBffPrincipal principals;
    private final String gatewayToken;

    public CustomerNotificationBffController(NotificationController internal, TaskBffPrincipal principals,
                                             @Value("${app.tool-gateway-token:}") String gatewayToken) {
        this.internal = internal;
        this.principals = principals;
        this.gatewayToken = gatewayToken;
    }

    @GetMapping
    public ApiEnvelope<List<NotificationController.Item>> list(JwtAuthenticationToken authentication,
                                                                 @RequestParam String requestId) {
        long customerId = principals.customer(authentication);
        return internal.list(customerId, ActorRole.CUSTOMER, gatewayToken, requestId);
    }

    @PostMapping("/{id}/read")
    public ApiEnvelope<Void> read(JwtAuthenticationToken authentication, @PathVariable long id,
                                  @RequestParam String requestId) {
        long customerId = principals.customer(authentication);
        return internal.read(id, customerId, ActorRole.CUSTOMER, gatewayToken, requestId);
    }
}
