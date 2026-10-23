package com.bankingax.core;

import static org.junit.jupiter.api.Assertions.*;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.caseport.CreateCaseCommand;
import com.bankingax.core.caseport.InMemoryCasePort;
import com.bankingax.core.caseport.ReviewStatus;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.LoanInquiryToolRequest;
import com.bankingax.core.tool.ToolActor;
import com.bankingax.core.tool.ToolGatewayService;
import com.bankingax.core.verification.InMemoryVerificationPort;
import com.bankingax.core.verification.VerificationAction;
import com.bankingax.core.verification.VerificationConsumeCommand;
import com.bankingax.core.workflow.InMemoryTaskHandler;
import com.bankingax.core.workflow.StartTaskCommand;
import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.WorkItemStatus;
import com.bankingax.task.a02.FakeLoanQueryPort;
import org.junit.jupiter.api.Test;

class Phase0ContractsTest {
    @Test void task_handler_allows_only_one_active_item_and_supports_hold_resume() {
        InMemoryTaskHandler handler = new InMemoryTaskHandler();
        var first = handler.start(new StartTaskCommand(1, 9, TaskCode.LOAN_INQUIRY, 0, "req-1"));
        assertThrows(DomainException.class, () -> handler.start(new StartTaskCommand(1, 9, TaskCode.DEBT_ADJUSTMENT, 0, "req-2")));
        var held = handler.hold(1, first.workItemId(), first.version(), "loan-selected");
        assertEquals(WorkItemStatus.ON_HOLD, held.status());
        assertEquals(WorkItemStatus.ACTIVE, handler.resume(1, first.workItemId(), held.version()).status());
    }
    @Test void tool_gateway_rechecks_customer_ownership() {
        ToolGatewayService gateway = new ToolGatewayService(new FakeLoanQueryPort());
        var response = gateway.loanInquiry(new ToolActor(1, ActorRole.CUSTOMER), new LoanInquiryToolRequest("req-a02", 1001L, 1, null));
        assertEquals(101L, response.loans().get(0).loanId());
        assertThrows(DomainException.class, () -> gateway.loanInquiry(new ToolActor(2, ActorRole.CUSTOMER), new LoanInquiryToolRequest("req-a02", 1001L, 1, null)));
    }
    @Test void case_fake_rejects_stale_worker_result() {
        InMemoryCasePort port = new InMemoryCasePort();
        var created = port.create(new CreateCaseCommand(1, 1001, TaskCode.DEBT_ADJUSTMENT, 101L, java.util.Map.of()));
        var submitted = port.submit(1, created.caseId(), created.revision(), "req-c01");
        port.revise(1, created.caseId(), submitted.revision(), java.util.Map.of("reason", "changed"), "CUSTOMER_CONFIRMATION");
        assertThrows(DomainException.class, () -> port.recordCheckResult(created.caseId(), submitted.revision(), ReviewStatus.READY_FOR_STAFF));
    }
    @Test void verification_binding_is_one_use_and_action_bound() {
        String[] capturedOtp = new String[1];
        InMemoryVerificationPort verification = new InMemoryVerificationPort((phone, otp) -> capturedOtp[0] = otp, 180, 5);
        var issue = verification.issue(new com.bankingax.core.verification.VerificationIssueCommand(1, 1001, VerificationAction.C01_SUBMIT, 0, "01012345821"));
        var binding = verification.verify(new com.bankingax.core.verification.VerificationVerifyCommand(1, issue.challengeId(), capturedOtp[0]));
        verification.consume(new VerificationConsumeCommand(binding.bindingId(), 1, 1001, VerificationAction.C01_SUBMIT, 0));
        assertThrows(DomainException.class, () -> verification.consume(new VerificationConsumeCommand(binding.bindingId(), 1, 1001, VerificationAction.C01_SUBMIT, 0)));
    }
}
