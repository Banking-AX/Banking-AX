package com.bankingax.task.a01;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.ToolGatewayController;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer JWT boundary for A-01; delegates to the existing internal Tool controller. */
@RestController
@RequestMapping("/api/v1/customer/a01")
public class A01CustomerBffController {
    private final ToolGatewayController tools;
    private final TaskBffPrincipal principals;
    private final String gatewayToken;
    private final A01WorkflowService workflow;

    public A01CustomerBffController(ToolGatewayController tools, TaskBffPrincipal principals,
                                    ObjectProvider<A01WorkflowService> workflow,
                                    @Value("${app.tool-gateway-token:}") String gatewayToken) {
        this.tools = tools;
        this.principals = principals;
        this.workflow = workflow.getIfAvailable();
        this.gatewayToken = gatewayToken;
    }

    @PostMapping("/transaction-search")
    public ApiEnvelope<TransactionSearchResponse> search(JwtAuthenticationToken authentication,
                                                           @RequestBody Request body) {
        long customerId = principals.customer(authentication);
        return tools.transactionSearch(customerId, ActorRole.CUSTOMER, gatewayToken,
                new TransactionSearchRequest(body.requestId(), body.workItemId(), customerId, body.accountId(),
                        body.startDate(), body.endDate(), body.transactionType(), body.page(), body.size(),
                        body.minimumAmount(), body.maximumAmount(), body.keyword(), body.sortOrder()));
    }

    @GetMapping("/work-items/{workItemId}/result")
    public ApiEnvelope<TransactionSearchResponse> result(JwtAuthenticationToken authentication,
                                                          @PathVariable long workItemId) {
        long customerId = principals.customer(authentication);
        if (workflow == null) throw new DomainException("A01_RESULT_UNAVAILABLE", "Persistent A-01 results are unavailable.");
        return ApiEnvelope.success("a01-result-" + workItemId, "TRANSACTION_SEARCH", workItemId,
                null, null, workflow.result(customerId, workItemId));
    }

    public record Request(String requestId, Long workItemId, long accountId, LocalDate startDate, LocalDate endDate,
                          TransactionDirection transactionType, Integer page, Integer size,
                          BigDecimal minimumAmount, BigDecimal maximumAmount, String keyword,
                          TransactionSortOrder sortOrder) { }
}
