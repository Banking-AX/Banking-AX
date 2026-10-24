package com.bankingax.task.c01;

import static org.junit.jupiter.api.Assertions.*;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.caseport.InMemoryCasePort;
import com.bankingax.core.verification.*;
import com.bankingax.core.workflow.InMemoryTaskHandler;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class C01IntakeServiceTest {
  @Test void normalMockIntakeConsumesBoundOtpOnceAndCreatesNoReservation() {
    RecordingSender sender=new RecordingSender(); C01IntakeService service=service(sender);
    var draft=service.start(new C01IntakeService.C01StartCommand(1,10,0,"start-1",101,"01012345678"));
    service.saveIntake(1,draft.caseId(),0,"소득 감소","최근 소득이 줄었습니다",200000,true);
    service.recordConsentAndMockSignature(1,draft.caseId(),0,true,true);
    var issue=service.issueOtp(1,draft.caseId(),0); var binding=service.verifyOtp(1,issue.challengeId(),sender.code);
    var submitted=service.submit(new C01IntakeService.C01SubmitCommand(1,draft.caseId(),0,"submit-1",binding.bindingId()));
    assertEquals("SUBMITTED",submitted.caseStatus()); assertEquals("PENDING",submitted.reviewStatus()); assertFalse(submitted.visitReservationCreated());
    assertEquals(submitted,service.submit(new C01IntakeService.C01SubmitCommand(1,draft.caseId(),0,"submit-1",binding.bindingId())));
    assertThrows(DomainException.class,()->service.submit(new C01IntakeService.C01SubmitCommand(1,draft.caseId(),0,"submit-2",binding.bindingId())));
  }
  @Test void ownershipConsentAndRevisionAreChecked() {
    RecordingSender sender=new RecordingSender(); C01IntakeService service=service(sender);
    assertThrows(DomainException.class,()->service.start(new C01IntakeService.C01StartCommand(1,11,0,"not-owner",102,"0101")));
    var draft=service.start(new C01IntakeService.C01StartCommand(1,12,0,"start-2",101,"0101"));
    service.saveIntake(1,draft.caseId(),0,"사유","상황",1,true);
    assertThrows(DomainException.class,()->service.issueOtp(1,draft.caseId(),0));
    service.recordConsentAndMockSignature(1,draft.caseId(),0,true,true);
    service.revise(1,draft.caseId(),0,"추가 사실");
    assertThrows(DomainException.class,()->service.issueOtp(1,draft.caseId(),0));
  }
  @Test void systemRulesBlockAndStaffRulesRemainReviewItems() {
    DebtAdjustmentRuleValidator validator=new DebtAdjustmentRuleValidator();
    LocalDate today=LocalDate.now();
    assertRule(validator,new C01LoanProfile(1,1,false,1,today.minusMonths(4),false,false,false,true,false),"NOT_OVERDUE","BLOCKED");
    assertRule(validator,new C01LoanProfile(1,1,true,30_000_000,today.minusMonths(4),false,false,false,true,false),"PRINCIPAL_OVER_LIMIT","BLOCKED");
    assertRule(validator,new C01LoanProfile(1,1,true,1,today.minusMonths(1),false,false,false,true,false),"RECENT_TERMINATION","BLOCKED");
    assertRule(validator,new C01LoanProfile(1,1,true,1,today.minusMonths(4),true,false,false,true,false),"IN_LITIGATION","BLOCKED");
    var staff=validator.evaluate(new C01LoanProfile(1,1,true,1,today.minusMonths(4),false,true,true,false,true),today);
    assertTrue(staff.submissionAllowed());
    assertTrue(staff.items().stream().anyMatch(i->i.code().equals("OTHER_PROCEDURE")&&i.disposition().equals("NEEDS_STAFF_REVIEW")));
    assertTrue(staff.items().stream().anyMatch(i->i.ruleCode().equals("C1-W1")));
    assertTrue(staff.items().stream().anyMatch(i->i.ruleCode().equals("C1-W2")));
    assertTrue(staff.items().stream().anyMatch(i->i.ruleCode().equals("C1-W3")));
  }
  private static void assertRule(DebtAdjustmentRuleValidator validator,C01LoanProfile profile,String code,String disposition){ assertTrue(validator.evaluate(profile,LocalDate.now()).items().stream().anyMatch(i->i.code().equals(code)&&i.disposition().equals(disposition))); }
  private static C01IntakeService service(RecordingSender sender) { return new C01IntakeService(new InMemoryTaskHandler(),new InMemoryCasePort(),new InMemoryVerificationPort(sender,180,5),new FakeDebtAdjustmentLoanPort(),new DebtAdjustmentRuleValidator()); }
  static final class RecordingSender implements OtpSender { String code; public void send(String phone,String code){this.code=code;} }
}
