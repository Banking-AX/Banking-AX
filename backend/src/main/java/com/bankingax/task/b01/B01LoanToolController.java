package com.bankingax.task.b01;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tools/b01")
public class B01LoanToolController {
    private final InternalGatewayAuthenticator authenticator;
    private final B01LoanListService loans;
    private final B01ProgressService progress;

    B01LoanToolController(InternalGatewayAuthenticator authenticator, B01LoanListService loans,
                          B01ProgressService progress) {
        this.authenticator = authenticator;
        this.loans = loans;
        this.progress = progress;
    }

    @PostMapping("/loans")
    public ApiEnvelope<LoanCandidates> loans(
            @RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,
            @RequestHeader("X-Tool-Gateway-Token") String gatewayToken,
            @RequestBody LoanRequest request) {
        authenticator.verify(gatewayToken);
        requireCustomer(actorId, role);
        validate(request);
        List<LoanCandidate> candidates = loans.findOwnedLoansForTool(actorId, request.workItemId()).stream()
                .map(loan -> new LoanCandidate(
                        loan.loanId(), loan.loanName(), loan.maskedAccountNumber(), loan.currentRepaymentOrder()))
                .toList();
        return ApiEnvelope.success(
                request.requestId(), "REPAYMENT_ORDER_CHANGE", request.workItemId(), null, null,
                new LoanCandidates(candidates));
    }

    @PostMapping("/selection")
    public ApiEnvelope<B01ProgressService.SelectionSaveResult> saveSelection(
            @RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,
            @RequestHeader("X-Tool-Gateway-Token") String gatewayToken,
            @RequestBody SelectionRequest request) {
        authenticator.verify(gatewayToken);
        requireCustomer(actorId, role);
        validate(request);
        B01ProgressService.SelectionSaveResult result = progress.saveSelectionForTool(
                actorId, request.workItemId(), new B01ProgressService.SelectionCommand(
                        request.requestId(), request.selectedLoanId(), request.requestedRepaymentOrder()));
        return ApiEnvelope.success(
                request.requestId(), "REPAYMENT_ORDER_CHANGE", request.workItemId(), null, null, result);
    }

    private static void validate(LoanRequest request) {
        if (request == null || request.workItemId() <= 0 || request.requestId() == null
                || request.requestId().isBlank() || request.requestId().length() > 90) {
            throw new DomainException("INVALID_REQUEST", "A valid requestId and workItemId are required.");
        }
    }

    private static void validate(SelectionRequest request) {
        if (request == null || request.workItemId() <= 0 || request.selectedLoanId() <= 0
                || request.requestId() == null || request.requestId().isBlank()
                || request.requestId().length() > 64) {
            throw new DomainException("INVALID_REQUEST", "Valid selection details are required.");
        }
    }

    private static void requireCustomer(long actorId, ActorRole role) {
        if (role != ActorRole.CUSTOMER || actorId <= 0) {
            throw new DomainException("FORBIDDEN", "Customer ownership cannot be verified.");
        }
    }

    public record LoanRequest(String requestId, long workItemId) { }

    public record SelectionRequest(String requestId, long workItemId, long selectedLoanId,
                                   String requestedRepaymentOrder) { }

    public record LoanCandidates(List<LoanCandidate> loans) { }

    public record LoanCandidate(long loanId, String loanName, String loanNoMasked, String repaymentOrder) { }
}
