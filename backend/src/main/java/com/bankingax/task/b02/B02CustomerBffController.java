package com.bankingax.task.b02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.verification.VerificationBinding;
import com.bankingax.core.verification.VerificationIssue;
import com.bankingax.core.api.DomainException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer JWT boundary for B-02; preserves the distinct account and WorkItem IDs. */
@RestController
@RequestMapping("/api/v1/customer/b02")
public class B02CustomerBffController {
    private final B02Controller internal;
    private final B02WorkflowService workflow;
    private final TaskBffPrincipal principals;
    private final String gatewayToken;

    public B02CustomerBffController(B02Controller internal, ObjectProvider<B02WorkflowService> workflow, TaskBffPrincipal principals,
                                    @Value("${app.tool-gateway-token:}") String gatewayToken) {
        this.internal = internal;
        this.workflow = workflow.getIfAvailable();
        this.principals = principals;
        this.gatewayToken = gatewayToken;
    }

    @PostMapping("/prepare")
    public ApiEnvelope<B02Service.P> prepare(JwtAuthenticationToken authentication, @RequestBody Prepare body) {
        long customerId = principals.customer(authentication);
        return internal.p(customerId, ActorRole.CUSTOMER, gatewayToken,
                new B02Controller.Q(body.requestId(), customerId, body.account(), body.waive(), body.taxed()));
    }

    @PostMapping("/{accountId}/otp")
    public ApiEnvelope<VerificationIssue> issue(JwtAuthenticationToken authentication, @PathVariable long accountId,
                                                @RequestBody Otp body) {
        long customerId = principals.customer(authentication);
        return internal.i(accountId, customerId, ActorRole.CUSTOMER, gatewayToken,
                new B02Controller.O(body.requestId(), customerId, body.version(), body.consent(),
                        body.signed(), body.phone(), body.workItemId()));
    }

    @PostMapping("/otp/verify")
    public ApiEnvelope<VerificationBinding> verify(JwtAuthenticationToken authentication, @RequestBody Verify body) {
        long customerId = principals.customer(authentication);
        return internal.v(customerId, ActorRole.CUSTOMER, gatewayToken,
                new B02Controller.V(body.requestId(), customerId, body.challenge(), body.otp()));
    }

    @PostMapping("/{accountId}/execute")
    public ApiEnvelope<B02Service.R> execute(JwtAuthenticationToken authentication, @PathVariable long accountId,
                                             @RequestBody Execute body) {
        long customerId = principals.customer(authentication);
        return internal.e(accountId, customerId, ActorRole.CUSTOMER, gatewayToken,
                new B02Controller.E(body.requestId(), customerId, body.version(), body.waive(), body.taxed(),
                        body.binding(), body.workItemId()));
    }

    @org.springframework.web.bind.annotation.GetMapping("/work-items/{workItemId}/result")
    public ApiEnvelope<B02WorkflowService.StoredResult> result(JwtAuthenticationToken authentication,
                                                                @PathVariable long workItemId) {
        long customerId = principals.customer(authentication);
        if (workflow == null) throw new DomainException("B02_RESULT_UNAVAILABLE", "Persistent B-02 results are unavailable.");
        return ApiEnvelope.success("b02-result-" + workItemId, "HOUSING_SAVINGS_CLOSE", workItemId,
                null, null, workflow.result(customerId, workItemId));
    }

    public record Prepare(String requestId, long account, boolean waive, Boolean taxed) { }
    public record Otp(String requestId, int version, boolean consent, boolean signed, String phone, Long workItemId) { }
    public record Verify(String requestId, String challenge, String otp) { }
    public record Execute(String requestId, int version, boolean waive, Boolean taxed, String binding, Long workItemId) { }
}
